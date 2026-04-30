package com.jingyicare.aims_jd.service;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.*;
import com.jingyicare.aims_jd.tool.*;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;
import com.jingyicare.aims_jd.utils.*;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.*;

@Service
@Slf4j
public class DeviceConnManager implements DriverDefinitionRepository {
    public DeviceConnManager(
        ConfigurableApplicationContext context,
        @Value("${device.driver.txt:}") String driverPath,
        @Value("${device.sync.interval_mins:3}") int syncIntervalMins,
        @Value("${device.heartbeat.interval_ms:1000}") int heartbeatIntervalMs,
        @Value("${device.sync.await_termination_seconds:60}") int awaitTerminationSeconds,
        @Value("${conn.threadpool.size:100}") int connPoolSize,
        @Value("${conn.threadpool.queue_capacity:10000}") int connPoolQueueCapacity,
        @Value("${conn.threadpool.keepalive_seconds:120}") int connPoolKeepAliveSeconds,
        @Value("${conn.threadpool.await_termination_seconds:60}") int connPoolAwaitTerminationSeconds,
        @Autowired AimsDeviceRepository deviceRepository,
        @Autowired ObservationPublisher observationPublisher,
        @Autowired PrometheusMetricService metricService,
        @Autowired TxtDumper txtDumper
    ) {
        this.syncIntervalMins = syncIntervalMins;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.awaitTerminationSeconds = awaitTerminationSeconds;
        this.connPoolAwaitTerminationSeconds = connPoolAwaitTerminationSeconds;
        this.deviceRepository = deviceRepository;
        this.observationPublisher = observationPublisher;
        this.metricService = metricService;
        this.txtDumper = txtDumper;

        driverMap = new HashMap<>();
        driverParamMap = new HashMap<>();
        initDriverConfigs(driverPath, context);

        sourceDevices = new ConcurrentHashMap<>();
        deviceSyncSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AimsDeviceSyncThread");
            t.setDaemon(true);
            return t;
        });

        deviceConnHandlers = new ConcurrentHashMap<>();
        heartbeatSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DeviceHeartbeatThread");
            t.setDaemon(true);
            return t;
        });

        connRunQueue = new ArrayBlockingQueue<>(connPoolQueueCapacity);
        int cores = Math.min(connPoolSize, Runtime.getRuntime().availableProcessors());
        connExecutor = new ThreadPoolExecutor(
            cores, connPoolSize, connPoolKeepAliveSeconds, TimeUnit.SECONDS, connRunQueue, new ConnRejectedHandler()
        );
    }

    @PostConstruct
    public void init() {
        deviceSyncSched.scheduleWithFixedDelay(() -> {
            log.info("Scheduled AIMS device sync");
            syncAimsDevices();
        }, 0, syncIntervalMins, TimeUnit.MINUTES);
        log.info("device info syncIntervalMins: {}", syncIntervalMins);

        heartbeatSched.scheduleWithFixedDelay(this::heartbeatDevices, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        log.info("device heartbeat intervalMs: {}", heartbeatIntervalMs);
    }

    @PreDestroy
    public void shutdown() {
        shutdownAndAwait(deviceSyncSched, awaitTerminationSeconds, TimeUnit.SECONDS);
        shutdownAndAwait(heartbeatSched, awaitTerminationSeconds, TimeUnit.SECONDS);

        sourceDevices.clear();
        for (DeviceConnHandler handler : deviceConnHandlers.values()) {
            if (handler != null) {
                handler.close(null);
            }
        }
        deviceConnHandlers.clear();

        connExecutor.shutdown();
        try {
            if (!connExecutor.awaitTermination(connPoolAwaitTerminationSeconds, TimeUnit.SECONDS)) {
                connExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            connExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void initDriverConfigs(String driverPath, ConfigurableApplicationContext context) {
        if (driverPath == null || driverPath.isEmpty()) {
            log.error("Device driver txt path is blank.");
            LogUtils.flushAndQuit(context);
        }

        try {
            String resourceLocation = driverPath;
            boolean isUrl = ResourceUtils.isUrl(resourceLocation);
            if (!isUrl) {
                resourceLocation = ResourceUtils.FILE_URL_PREFIX + resourceLocation.replace('\\', '/');
            }
            Resource driverResource = context.getResource(resourceLocation);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(driverResource.getInputStream(), Consts.CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            DevObservationConfigsPB devObsConfigs = ProtoUtils.txtToProto(sb.toString());
            if (devObsConfigs == null || devObsConfigs.getConfigCount() == 0) {
                log.error("No valid device driver config found in txt file.");
                LogUtils.flushAndQuit(context);
            }

            for (DevObservationConfigPB config : devObsConfigs.getConfigList()) {
                String driverCode = config.getDriverCode();
                Map<String, String> paramMap = new HashMap<>();
                for (DevObservationItemPB item : config.getItemList()) {
                    paramMap.put(item.getObCode(), item.getMonitoringParamCode());
                }
                driverParamMap.put(driverCode, paramMap);
                driverMap.put(driverCode, config);
            }
            log.info("Loaded {} AIMS JD driver configs.", devObsConfigs.getConfigCount());
        } catch (Exception e) {
            log.error("Failed to read device driver txt file: {}", e.getMessage(), e);
            LogUtils.flushAndQuit(context);
        }
    }

    private void syncAimsDevices() {
        List<DeviceInfoPB> devices = deviceRepository.findActiveDevices(driverMap.keySet());
        Map<String, DeviceInfoPB> nextSources = new HashMap<>();
        int totalDevices = 0;
        int inboundSources = 0;
        Set<String> duplicateIps = new HashSet<>();

        for (DeviceInfoPB device : devices) {
            totalDevices++;
            if (!DeviceSourceModes.isInboundServer(device)) {
                continue;
            }
            String ip = normalizeIp(device.getDeviceIp());
            if (ip.isEmpty()) {
                log.warn("Skip inbound device with empty IP: id={} driverCode={}",
                    device.getId(), device.getDeviceDriverCode());
                continue;
            }
            if (nextSources.put(ip, device) != null) {
                duplicateIps.add(ip);
            }
        }
        for (String ip : duplicateIps) {
            nextSources.remove(ip);
            log.error("Skip duplicated inbound device IP: {}", ip);
        }
        inboundSources = nextSources.size();

        sourceDevices.clear();
        sourceDevices.putAll(nextSources);
        log.info("Synced {} inbound source devices from AIMS ({} active supported device definitions).",
            inboundSources, totalDevices);

        metricService.gaugeDeviceCount(() -> sourceDevices.size());

        for (Map.Entry<String, DeviceConnHandler> entry : deviceConnHandlers.entrySet()) {
            String ip = entry.getKey();
            if (!sourceDevices.containsKey(ip)) {
                DeviceConnHandler oldHandler = deviceConnHandlers.remove(ip);
                if (oldHandler != null) {
                    connExecutor.submit(new ConnRunnable(oldHandler, DeviceConnHandler.OpType.HANDLE_CLOSE, null));
                }
            }
        }
    }

    private void heartbeatDevices() {
        for (Map.Entry<String, DeviceConnHandler> entry : deviceConnHandlers.entrySet()) {
            String ip = entry.getKey();
            DeviceConnHandler connHandler = entry.getValue();

            if (!connHandler.isAlive()) {
                deviceConnHandlers.remove(ip, connHandler);
            } else {
                connExecutor.submit(new ConnRunnable(connHandler, DeviceConnHandler.OpType.HANDLE_HEARTBEAT, null));
            }
        }
    }

    public void accept(SocketChannel client, Selector selector, SelectionKey key) {
        String remoteIp = getRemoteIpFromSocketChannel(client);
        if (remoteIp == null || remoteIp.isEmpty()) {
            log.warn("Rejected connection from unknown IP.");
            try { client.close(); } catch (IOException ignore) {}
            return;
        }

        DeviceInfoPB deviceInfo = sourceDevices.get(remoteIp);
        if (deviceInfo == null) {
            log.warn("Rejected connection from unconfigured AIMS device: {}", remoteIp);
            metricService.incrementNonBridgeDeviceClosed(remoteIp);
            try { client.close(); } catch (IOException ignore) {}
            return;
        }

        String driverCode = deviceInfo.getDeviceDriverCode();
        DevObservationConfigPB obsConfig = driverMap.get(driverCode);
        Map<String, String> paramMap = driverParamMap.get(driverCode);
        if (obsConfig == null || paramMap == null) {
            log.warn("Rejected connection from device with unknown driver code: {} ({})", remoteIp, driverCode);
            metricService.incrementNoDriverDeviceClosed(driverCode, remoteIp);
            try { client.close(); } catch (IOException ignore) {}
            return;
        }

        try {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, remoteIp);
            log.info("New AIMS device connection from {}", remoteIp);
        } catch (IOException e) {
            log.warn("Failed to register new connection from {}: {}", remoteIp, e.getMessage());
            try { client.close(); } catch (IOException ignore) {}
            return;
        }

        DeviceConnHandler newHandler = DeviceConnFactory.newHandler(
            driverCode, client, remoteIp, deviceInfo, obsConfig, paramMap,
            observationPublisher, metricService, txtDumper
        );
        if (newHandler == null) {
            log.warn("Rejected connection from device with unsupported driver code: {} ({})", remoteIp, driverCode);
            metricService.incrementNoDriverImplClosed(driverCode, remoteIp);
            try { client.close(); } catch (IOException ignore) {}
            return;
        }
        DeviceConnHandler oldHandler = deviceConnHandlers.put(remoteIp, newHandler);
        if (oldHandler != null) {
            connExecutor.submit(new ConnRunnable(oldHandler, DeviceConnHandler.OpType.HANDLE_CLOSE, null));
        }
    }

    public void read(String ip, SelectionKey key) {
        if (key == null || !key.isValid()) {
            return;
        }
        DeviceConnHandler connHandler = deviceConnHandlers.get(ip);
        if (connHandler == null) {
            log.warn("No connection handler found for readable device: {}", ip);
            try { key.cancel(); } catch (Exception ignore) {}
            return;
        }
        connExecutor.submit(new ConnRunnable(connHandler, DeviceConnHandler.OpType.HANDLE_READ, key));
    }

    public Set<String> supportedDriverCodes() {
        return Set.copyOf(driverMap.keySet());
    }

    @Override
    public Optional<DevObservationConfigPB> findObservationConfig(String driverCode) {
        if (driverCode == null || driverCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(driverMap.get(driverCode));
    }

    @Override
    public Map<String, String> findParamMap(String driverCode) {
        if (driverCode == null || driverCode.isBlank()) {
            return Map.of();
        }
        Map<String, String> paramMap = driverParamMap.get(driverCode);
        return paramMap == null ? Map.of() : Map.copyOf(paramMap);
    }

    private static String getRemoteIpFromSocketChannel(SocketChannel channel) {
        if (channel == null) {
            return "";
        }
        try {
            SocketAddress remote = channel.getRemoteAddress();
            if (remote instanceof InetSocketAddress inetSocketAddress) {
                InetAddress addr = inetSocketAddress.getAddress();
                return addr != null ? addr.getHostAddress() : "";
            }
            return "";
        } catch (IOException e) {
            log.warn("Failed to get remote address from SocketChannel: {}", e.toString());
            return "";
        }
    }

    private static String normalizeIp(String ip) {
        return ip == null ? "" : ip.trim();
    }

    private static void shutdownAndAwait(ExecutorService es, long timeout, TimeUnit unit) {
        es.shutdown();
        try {
            if (!es.awaitTermination(timeout, unit)) {
                es.shutdownNow();
            }
        } catch (InterruptedException ie) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final long syncIntervalMins;
    private final long heartbeatIntervalMs;
    private final long awaitTerminationSeconds;
    private final long connPoolAwaitTerminationSeconds;

    private final Map<String, DevObservationConfigPB> driverMap;
    private final Map<String, Map<String, String>> driverParamMap;

    private final ConcurrentHashMap<String, DeviceInfoPB> sourceDevices;
    private final ConcurrentHashMap<String, DeviceConnHandler> deviceConnHandlers;

    private final ScheduledExecutorService deviceSyncSched;
    private final ScheduledExecutorService heartbeatSched;
    private final BlockingQueue<Runnable> connRunQueue;
    private final ThreadPoolExecutor connExecutor;

    private final AimsDeviceRepository deviceRepository;
    private final ObservationPublisher observationPublisher;
    private final PrometheusMetricService metricService;
    private final TxtDumper txtDumper;
}

