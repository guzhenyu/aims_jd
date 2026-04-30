package com.jingyicare.aims_jd.service;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.DeviceConnFactory;
import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.frame.FrameAccumulator;
import com.jingyicare.aims_jd.driver.impl.BltQ5Monitor;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.driver.session.SessionRuntime;
import com.jingyicare.aims_jd.tool.PrometheusMetricService;
import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;
import com.jingyicare.aims_jd.utils.Consts;
import com.jingyicare.aims_jd.utils.DeviceSourceModes;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.*;

@Service
@Slf4j
public class OutboundDeviceManager {
    public OutboundDeviceManager(
        AimsDeviceRepository deviceRepository,
        DeviceConnManager deviceConnManager,
        DriverDefinitionRepository driverDefinitions,
        ObservationPublisher observationPublisher,
        PrometheusMetricService metricService,
        TxtDumper txtDumper,
        @Value("${device.sync.interval_mins:1}") int syncIntervalMins,
        @Value("${device.sync.await_termination_seconds:30}") int awaitTerminationSeconds,
        @Value("${outbound.connect.timeout_ms:5000}") int connectTimeoutMs,
        @Value("${outbound.read.timeout_ms:1500}") int readTimeoutMs,
        @Value("${outbound.reconnect.interval_ms:5000}") int reconnectIntervalMs,
        @Value("${bltq5.heartbeat.enabled:true}") boolean bltQ5HeartbeatEnabled,
        @Value("${bltq5.heartbeat.interval_ms:1000}") int bltQ5HeartbeatIntervalMs,
        @Value("${bltq5.query.enabled:true}") boolean bltQ5QueryEnabled,
        @Value("${bltq5.query.interval_ms:1000}") int bltQ5QueryIntervalMs,
        @Value("${bltq5.query.timestamp_suffix:true}") boolean bltQ5QueryTimestampSuffix
    ) {
        this.deviceRepository = deviceRepository;
        this.deviceConnManager = deviceConnManager;
        this.driverDefinitions = driverDefinitions;
        this.observationPublisher = observationPublisher;
        this.metricService = metricService;
        this.txtDumper = txtDumper;
        this.syncIntervalMins = syncIntervalMins;
        this.awaitTerminationSeconds = awaitTerminationSeconds;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.bltQ5HeartbeatEnabled = bltQ5HeartbeatEnabled;
        this.bltQ5HeartbeatIntervalMs = bltQ5HeartbeatIntervalMs;
        this.bltQ5QueryEnabled = bltQ5QueryEnabled;
        this.bltQ5QueryIntervalMs = bltQ5QueryIntervalMs;
        this.bltQ5QueryTimestampSuffix = bltQ5QueryTimestampSuffix;
        this.sessions = new ConcurrentHashMap<>();
        this.syncSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AimsOutboundDeviceSyncThread");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        syncSched.scheduleWithFixedDelay(this::syncOutboundDevices, 0, syncIntervalMins, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        syncSched.shutdown();
        try {
            if (!syncSched.awaitTermination(awaitTerminationSeconds, TimeUnit.SECONDS)) {
                syncSched.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncSched.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessions.values().forEach(OutboundSession::stop);
        sessions.clear();
    }

    private void syncOutboundDevices() {
        List<DeviceInfoPB> devices = deviceRepository.findActiveDevices(deviceConnManager.supportedDriverCodes());
        Map<Integer, DeviceInfoPB> desired = new HashMap<>();
        for (DeviceInfoPB device : devices) {
            if (!DeviceSourceModes.isOutboundClient(device)) {
                continue;
            }
            if (device.getDeviceIp().isBlank() || !isValidPort(device.getDevicePort())) {
                log.warn("Skip outbound device with invalid endpoint: id={} ip={} port={} driverCode={}",
                    device.getId(), device.getDeviceIp(), device.getDevicePort(), device.getDeviceDriverCode());
                continue;
            }
            desired.put(device.getId(), device);
        }

        sessions.entrySet().removeIf(entry -> {
            DeviceInfoPB desiredDevice = desired.get(entry.getKey());
            if (desiredDevice == null || !sameEndpointAndDriver(entry.getValue().device(), desiredDevice)) {
                entry.getValue().stop();
                return true;
            }
            return false;
        });

        for (DeviceInfoPB device : desired.values()) {
            sessions.computeIfAbsent(device.getId(), ignored -> {
                OutboundSession session = new OutboundSession(device);
                session.start();
                return session;
            });
        }
        log.info("Reconciled {} outbound source devices", sessions.size());
    }

    private boolean sameEndpointAndDriver(DeviceInfoPB oldDevice, DeviceInfoPB newDevice) {
        return Objects.equals(oldDevice.getDeviceIp(), newDevice.getDeviceIp())
            && Objects.equals(oldDevice.getDevicePort(), newDevice.getDevicePort())
            && Objects.equals(oldDevice.getDeviceDriverCode(), newDevice.getDeviceDriverCode());
    }

    private boolean isValidPort(String port) {
        if (port == null || port.isBlank()) {
            return false;
        }
        try {
            int parsed = Integer.parseInt(port.trim());
            return parsed >= 0 && parsed <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private final class OutboundSession implements Runnable {
        private OutboundSession(DeviceInfoPB device) {
            this.device = device;
            this.running = new AtomicBoolean(false);
        }

        DeviceInfoPB device() {
            return device;
        }

        void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            thread = new Thread(this, "AimsOutboundDevice-" + device.getId());
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running.set(false);
            closeQuietly(socket);
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            while (running.get()) {
                try {
                    connectAndCollect();
                } catch (Exception e) {
                    log.warn("Outbound device session error: id={} endpoint={}:{} err={}",
                        device.getId(), device.getDeviceIp(), device.getDevicePort(), e.toString());
                } finally {
                    closeQuietly(socket);
                    socket = null;
                }
                sleep(reconnectIntervalMs);
            }
        }

        private void connectAndCollect() throws Exception {
            DevObservationConfigPB config = driverDefinitions.findObservationConfig(device.getDeviceDriverCode())
                .orElseThrow(() -> new IllegalStateException("No driver config: " + device.getDeviceDriverCode()));
            Map<String, String> paramMap = driverDefinitions.findParamMap(device.getDeviceDriverCode());
            Optional<DriverPluginFactory> factory = DeviceConnFactory.driverRegistry().find(device.getDeviceDriverCode());
            if (factory.isEmpty()) {
                throw new IllegalStateException("No driver implementation: " + device.getDeviceDriverCode());
            }

            socket = new Socket();
            socket.connect(new InetSocketAddress(device.getDeviceIp(), Integer.parseInt(device.getDevicePort())), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            log.info("Connected outbound AIMS device: id={} endpoint={}:{} driverCode={}",
                device.getId(), device.getDeviceIp(), device.getDevicePort(), device.getDeviceDriverCode());

            DriverContext driverContext = new DriverContext(
                device.getDeviceIp(),
                device,
                config,
                paramMap,
                config.getObsPageList(),
                Consts.CHARSET,
                Consts.ZONE_ID
            );
            DriverPlugin plugin = factory.get().create(driverContext);
            RuntimeProtocolSessionContext sessionContext = new RuntimeProtocolSessionContext(driverContext);
            SessionRuntime runtime = new SessionRuntime(
                new FrameAccumulator(plugin.frameDecoder()),
                plugin.protocolSession(),
                sessionContext
            );
            runtime.onConnected();

            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[Consts.CHANNEL_READ_BUFFER_SIZE];
            long lastHeartbeatAt = 0;
            long lastQueryAt = 0;

            while (running.get() && socket != null && !socket.isClosed()) {
                long now = System.currentTimeMillis();
                if (BltQ5Monitor.DRIVER_CODE.equals(device.getDeviceDriverCode())) {
                    if (bltQ5HeartbeatEnabled && now - lastHeartbeatAt >= bltQ5HeartbeatIntervalMs) {
                        writeCommand(buildBltQ5Heartbeat());
                        lastHeartbeatAt = now;
                    }
                    if (bltQ5QueryEnabled && now - lastQueryAt >= bltQ5QueryIntervalMs) {
                        writeCommand(buildBltQ5Query());
                        lastQueryAt = now;
                    }
                }
                runtime.onTick();

                try {
                    int n = in.read(buffer);
                    if (n < 0) {
                        throw new EOFException("remote closed");
                    }
                    if (n > 0) {
                        byte[] chunk = Arrays.copyOf(buffer, n);
                        sessionContext.touchHeartbeat();
                        runtime.onBytes(chunk);
                    }
                } catch (SocketTimeoutException timeout) {
                    // normal poll timeout
                }
            }
            runtime.onDisconnected();
        }

        private byte[] buildBltQ5Heartbeat() {
            return buildMllpFrame("MSH|^~\\&|||||||ORU^R01|HR01|P|2.3.1|\r");
        }

        private byte[] buildBltQ5Query() {
            byte[] frame = buildMllpFrame(
                "MSH|^~\\&|||||||ORU^R02|QY01|P|2.3.1|\r"
                    + "QRD||R|I|QCM2010|||||RES|\r"
                    + "QRF|CMS||||0&0^1^1^0^1|\r"
            );
            if (!bltQ5QueryTimestampSuffix) {
                return frame;
            }
            byte[] suffix = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now())
                .getBytes(StandardCharsets.US_ASCII);
            byte[] combined = Arrays.copyOf(frame, frame.length + suffix.length);
            System.arraycopy(suffix, 0, combined, frame.length, suffix.length);
            return combined;
        }

        private byte[] buildMllpFrame(String payload) {
            byte[] body = payload.getBytes(StandardCharsets.US_ASCII);
            byte[] frame = new byte[body.length + 3];
            frame[0] = Consts.VT;
            System.arraycopy(body, 0, frame, 1, body.length);
            frame[body.length + 1] = Consts.FS;
            frame[body.length + 2] = Consts.CR;
            return frame;
        }

        private void writeCommand(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length == 0 || socket == null || socket.isClosed()) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
        }

        private final class RuntimeProtocolSessionContext implements ProtocolSessionContext {
            private RuntimeProtocolSessionContext(DriverContext driverContext) {
                this.driverContext = driverContext;
            }

            @Override
            public DriverContext driverContext() {
                return driverContext;
            }

            @Override
            public ObservationPublisher publisher() {
                return observationPublisher;
            }

            @Override
            public PrometheusMetricService metricService() {
                return metricService;
            }

            @Override
            public TxtDumper txtDumper() {
                return txtDumper;
            }

            @Override
            public com.jingyicare.aims_jd.driver.session.OutboundCommandSender commandSender() {
                return (bytes, delayMs) -> {
                    if (delayMs > 0) {
                        sleep(delayMs);
                    }
                    try {
                        writeCommand(bytes);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to write outbound command", e);
                    }
                };
            }

            @Override
            public String remoteIp() {
                return device.getDeviceIp();
            }

            @Override
            public void touchHeartbeat() {
                lastHeartbeat = System.currentTimeMillis();
            }

            @Override
            public void markStale(String reason) {
                log.warn("Outbound session marked stale: id={} reason={}", device.getId(), reason);
                running.set(false);
            }

            private final DriverContext driverContext;
            private volatile long lastHeartbeat;
        }

        private final DeviceInfoPB device;
        private final AtomicBoolean running;
        private volatile Thread thread;
        private volatile Socket socket;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final AimsDeviceRepository deviceRepository;
    private final DeviceConnManager deviceConnManager;
    private final DriverDefinitionRepository driverDefinitions;
    private final ObservationPublisher observationPublisher;
    private final PrometheusMetricService metricService;
    private final TxtDumper txtDumper;
    private final int syncIntervalMins;
    private final int awaitTerminationSeconds;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int reconnectIntervalMs;
    private final boolean bltQ5HeartbeatEnabled;
    private final int bltQ5HeartbeatIntervalMs;
    private final boolean bltQ5QueryEnabled;
    private final int bltQ5QueryIntervalMs;
    private final boolean bltQ5QueryTimestampSuffix;
    private final ConcurrentHashMap<Integer, OutboundSession> sessions;
    private final ScheduledExecutorService syncSched;
}

