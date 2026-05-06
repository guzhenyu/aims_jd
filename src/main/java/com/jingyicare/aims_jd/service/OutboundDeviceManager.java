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
        @Value("${bltq5.query.timestamp_suffix:true}") boolean bltQ5QueryTimestampSuffix,
        @Value("${bltq5.query.once_per_connection:true}") boolean bltQ5QueryOncePerConnection,
        @Value("${bltq5.query.delay_ms:200}") int bltQ5QueryDelayMs,
        @Value("${bltq5.query.timestamp.zone_id:Asia/Shanghai}") String bltQ5QueryTimestampZoneId,
        @Value("${bltq5.ack.enabled:false}") boolean bltQ5AckEnabled,
        @Value("${bltq5.debug.enabled:true}") boolean bltQ5DebugEnabled,
        @Value("${bltq5.debug.dump_bytes:true}") boolean bltQ5DebugDumpBytes,
        @Value("${bltq5.debug.dump_max_bytes:512}") int bltQ5DebugDumpMaxBytes
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
        this.bltQ5QueryOncePerConnection = bltQ5QueryOncePerConnection;
        this.bltQ5QueryDelayMs = Math.max(0, bltQ5QueryDelayMs);
        this.bltQ5QueryTimestampZoneId = bltQ5QueryTimestampZoneId;
        this.bltQ5AckEnabled = bltQ5AckEnabled;
        this.bltQ5DebugEnabled = bltQ5DebugEnabled;
        this.bltQ5DebugDumpBytes = bltQ5DebugDumpBytes;
        this.bltQ5DebugDumpMaxBytes = Math.max(0, bltQ5DebugDumpMaxBytes);
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
        logOutboundDeviceStatus(devices);
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

    private void logOutboundDeviceStatus(List<DeviceInfoPB> devices) {
        StringBuilder builder = new StringBuilder("AIMS device_infos outbound status:");
        int count = 0;
        for (DeviceInfoPB device : devices) {
            if (!DeviceSourceModes.isOutboundClient(device)) {
                continue;
            }
            count++;
            OutboundSession session = sessions.get(device.getId());
            boolean connected = session != null && session.isConnected();
            String reason = outboundDisconnectedReason(device, session);

            builder.append('\n')
                .append("id=").append(device.getId())
                .append(" name=").append(blankToDash(device.getDeviceName()))
                .append(" ip=").append(blankToDash(device.getDeviceIp()))
                .append(" port=").append(blankToDash(device.getDevicePort()))
                .append(" driverCode=").append(blankToDash(device.getDeviceDriverCode()))
                .append(" sourceMode=").append(device.getSourceMode())
                .append(" status=").append(connected ? "connected" : "disconnected");
            if (!connected && !reason.isBlank()) {
                builder.append(" reason=").append(reason);
            }
        }
        if (count == 0) {
            builder.append('\n').append("(empty)");
        }
        log.info("{}", builder);
    }

    private String outboundDisconnectedReason(DeviceInfoPB device, OutboundSession session) {
        if (device.getDeviceIp().isBlank() || !isValidPort(device.getDevicePort())) {
            return "invalid_endpoint";
        }
        if (session == null) {
            return "session_not_started";
        }
        return "not_connected";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private Map<String, String> driverOptions(DeviceInfoPB device) {
        if (device == null || !BltQ5Monitor.DRIVER_CODE.equals(device.getDeviceDriverCode())) {
            return Map.of();
        }
        Map<String, String> options = new HashMap<>();
        options.put(BltQ5Monitor.OPTION_ACK_ENABLED, Boolean.toString(bltQ5AckEnabled));
        options.put(BltQ5Monitor.OPTION_DIAGNOSTIC_ENABLED, Boolean.toString(bltQ5DebugEnabled));
        return options;
    }

    private final class OutboundSession implements Runnable {
        private OutboundSession(DeviceInfoPB device) {
            this.device = device;
            this.running = new AtomicBoolean(false);
        }

        DeviceInfoPB device() {
            return device;
        }

        boolean isConnected() {
            Socket current = socket;
            return running.get()
                && current != null
                && current.isConnected()
                && !current.isClosed();
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
                    log.warn("Outbound device session error: id={} endpoint={}:{} local={} connectedMs={} bytesIn={} bytesOut={} lastReadAgeMs={} lastWriteAgeMs={} err={}",
                        device.getId(), device.getDeviceIp(), device.getDevicePort(), localEndpoint,
                        connectedDurationMs(), bytesIn, bytesOut, ageMs(lastReadAt), ageMs(lastWriteAt), e.toString(), e);
                } finally {
                    if (isBltQ5() && bltQ5DebugEnabled) {
                        log.info("BLT Q5 TCP closing: id={} endpoint={}:{} local={} connectedMs={} bytesIn={} bytesOut={}",
                            device.getId(), device.getDeviceIp(), device.getDevicePort(), localEndpoint,
                            connectedDurationMs(), bytesIn, bytesOut);
                    }
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

            resetConnectionStats();
            boolean q5 = isBltQ5();
            int attempt = ++connectAttempt;
            if (q5 && bltQ5DebugEnabled) {
                log.info("BLT Q5 TCP connect attempt: id={} attempt={} endpoint={}:{} connectTimeoutMs={} readTimeoutMs={} heartbeatEnabled={} heartbeatMs={} queryEnabled={} queryOnce={} queryDelayMs={} queryIntervalMs={} queryTimestampSuffix={} queryTimestampZone={} ackEnabled={}",
                    device.getId(), attempt, device.getDeviceIp(), device.getDevicePort(), connectTimeoutMs, readTimeoutMs,
                    bltQ5HeartbeatEnabled, bltQ5HeartbeatIntervalMs, bltQ5QueryEnabled, bltQ5QueryOncePerConnection,
                    bltQ5QueryDelayMs, bltQ5QueryIntervalMs, bltQ5QueryTimestampSuffix, bltQ5QueryTimestampZoneId,
                    bltQ5AckEnabled);
            }

            socket = new Socket();
            socket.connect(new InetSocketAddress(device.getDeviceIp(), Integer.parseInt(device.getDevicePort())), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            connectedAt = System.currentTimeMillis();
            localEndpoint = String.valueOf(socket.getLocalSocketAddress());
            log.info("Connected outbound AIMS device: id={} endpoint={}:{} driverCode={}",
                device.getId(), device.getDeviceIp(), device.getDevicePort(), device.getDeviceDriverCode());
            if (q5 && bltQ5DebugEnabled) {
                log.info("BLT Q5 TCP connected: id={} attempt={} local={} remote={} keepAlive={} tcpNoDelay={} receiveBuffer={} sendBuffer={}",
                    device.getId(), attempt, socket.getLocalSocketAddress(), socket.getRemoteSocketAddress(),
                    socket.getKeepAlive(), socket.getTcpNoDelay(), socket.getReceiveBufferSize(), socket.getSendBufferSize());
            }

            DriverContext driverContext = new DriverContext(
                device.getDeviceIp(),
                device,
                config,
                paramMap,
                config.getObsPageList(),
                Consts.CHARSET,
                Consts.ZONE_ID,
                driverOptions(device)
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
            boolean querySent = false;

            if (q5) {
                if (bltQ5HeartbeatEnabled) {
                    writeCommand("HR01 heartbeat", buildBltQ5Heartbeat());
                    lastHeartbeatAt = System.currentTimeMillis();
                }
                if (bltQ5QueryEnabled) {
                    sleep(bltQ5QueryDelayMs);
                    writeCommand("QY01/QCM2010 query", buildBltQ5Query());
                    lastQueryAt = System.currentTimeMillis();
                    querySent = true;
                }
            }

            while (running.get() && socket != null && !socket.isClosed()) {
                long now = System.currentTimeMillis();
                if (q5) {
                    if (bltQ5HeartbeatEnabled && now - lastHeartbeatAt >= bltQ5HeartbeatIntervalMs) {
                        writeCommand("HR01 heartbeat", buildBltQ5Heartbeat());
                        lastHeartbeatAt = now;
                    }
                    if (bltQ5QueryEnabled
                        && (!bltQ5QueryOncePerConnection || !querySent)
                        && now - lastQueryAt >= bltQ5QueryIntervalMs) {
                        writeCommand("QY01/QCM2010 query", buildBltQ5Query());
                        lastQueryAt = now;
                        querySent = true;
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
                        bytesIn += n;
                        lastReadAt = System.currentTimeMillis();
                        if (q5 && bltQ5DebugEnabled) {
                            log.info("BLT Q5 TCP <<< bytes={} totalIn={} id={} endpoint={}:{} local={}\n{}",
                                n, bytesIn, device.getId(), device.getDeviceIp(), device.getDevicePort(),
                                localEndpoint, formatBytes(chunk, n));
                        }
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
                .withZone(bltQ5QueryTimestampZone())
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

        private void writeCommand(String label, byte[] bytes) throws IOException {
            if (bytes == null || bytes.length == 0 || socket == null || socket.isClosed()) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
            bytesOut += bytes.length;
            lastWriteAt = System.currentTimeMillis();
            if (isBltQ5() && bltQ5DebugEnabled) {
                log.info("BLT Q5 TCP >>> {} bytes={} totalOut={} id={} endpoint={}:{} local={}\n{}",
                    label, bytes.length, bytesOut, device.getId(), device.getDeviceIp(), device.getDevicePort(),
                    localEndpoint, formatBytes(bytes, bytes.length));
            }
        }

        private boolean isBltQ5() {
            return BltQ5Monitor.DRIVER_CODE.equals(device.getDeviceDriverCode());
        }

        private void resetConnectionStats() {
            connectedAt = 0;
            bytesIn = 0;
            bytesOut = 0;
            lastReadAt = 0;
            lastWriteAt = 0;
            localEndpoint = "";
        }

        private long connectedDurationMs() {
            return connectedAt <= 0 ? 0 : System.currentTimeMillis() - connectedAt;
        }

        private long ageMs(long timestampMs) {
            return timestampMs <= 0 ? -1 : System.currentTimeMillis() - timestampMs;
        }

        private java.time.ZoneId bltQ5QueryTimestampZone() {
            String zone = bltQ5QueryTimestampZoneId == null || bltQ5QueryTimestampZoneId.isBlank()
                ? Consts.ZONE_ID
                : bltQ5QueryTimestampZoneId.trim();
            try {
                return java.time.ZoneId.of(zone);
            } catch (Exception e) {
                log.warn("Invalid bltq5.query.timestamp.zone_id={}, fallback={}", zone, Consts.ZONE_ID);
                return java.time.ZoneId.of(Consts.ZONE_ID);
            }
        }

        private String formatBytes(byte[] data, int len) {
            if (!bltQ5DebugDumpBytes) {
                return "(byte dump disabled)";
            }
            if (data == null || len <= 0 || bltQ5DebugDumpMaxBytes <= 0) {
                return "(empty)";
            }
            int dumpLen = Math.min(Math.min(data.length, len), bltQ5DebugDumpMaxBytes);
            StringBuilder builder = new StringBuilder();
            for (int off = 0; off < dumpLen; off += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    int idx = off + i;
                    if (idx < dumpLen) {
                        int b = data[idx] & 0xff;
                        hex.append(String.format(Locale.ROOT, "%02X ", b));
                        ascii.append(b >= 32 && b <= 126 ? (char) b : '.');
                    } else {
                        hex.append("   ");
                        ascii.append(' ');
                    }
                }
                if (off > 0) {
                    builder.append('\n');
                }
                builder.append(String.format(Locale.ROOT, "%04X  %-48s  %s", off, hex, ascii));
            }
            if (dumpLen < len) {
                builder.append('\n')
                    .append("... truncated, dumped ")
                    .append(dumpLen)
                    .append(" of ")
                    .append(len)
                    .append(" bytes");
            }
            return builder.toString();
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
                        writeCommand("driver command", bytes);
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
        private int connectAttempt;
        private volatile String localEndpoint = "";
        private volatile long connectedAt;
        private volatile long bytesIn;
        private volatile long bytesOut;
        private volatile long lastReadAt;
        private volatile long lastWriteAt;
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
    private final boolean bltQ5QueryOncePerConnection;
    private final int bltQ5QueryDelayMs;
    private final String bltQ5QueryTimestampZoneId;
    private final boolean bltQ5AckEnabled;
    private final boolean bltQ5DebugEnabled;
    private final boolean bltQ5DebugDumpBytes;
    private final int bltQ5DebugDumpMaxBytes;
    private final ConcurrentHashMap<Integer, OutboundSession> sessions;
    private final ScheduledExecutorService syncSched;
}

