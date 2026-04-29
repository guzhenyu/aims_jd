package com.jingyicare.aims_jd.driver;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.frame.FrameAccumulator;
import com.jingyicare.aims_jd.driver.session.OutboundCommandSender;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.driver.session.SessionRuntime;
import com.jingyicare.aims_jd.tool.PrometheusMetricService;
import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;

/**
 * 新骨架 runtime 的连接承载层。
 * 只负责 socket 生命周期和 SessionRuntime 对接，不再包含协议解析和发布细节。
 */
@Slf4j
public class PluginBackedDeviceConnHandler extends DeviceConnHandler {
    public PluginBackedDeviceConnHandler(
        SocketChannel socketChannel,
        String ip,
        DriverContext driverContext,
        DriverPlugin driverPlugin,
        ObservationPublisher publisher,
        PrometheusMetricService metricService,
        TxtDumper txtDumper
    ) {
        super(socketChannel, ip);
        this.driverContext = driverContext;
        this.driverPlugin = driverPlugin;
        this.publisher = publisher;
        this.metricService = metricService;
        this.txtDumper = txtDumper;
        this.frameAccumulator = new FrameAccumulator(driverPlugin.frameDecoder());
        this.sessionContext = new RuntimeProtocolSessionContext();
        this.sessionRuntime = new SessionRuntime(frameAccumulator, driverPlugin.protocolSession(), sessionContext);
        startSession();
    }

    public DriverContext driverContext() {
        return driverContext;
    }

    public DriverPlugin driverPlugin() {
        return driverPlugin;
    }

    @Override
    protected void onBytes(byte[] chunk, SelectionKey key) throws Exception {
        sessionRuntime.onBytes(chunk);
    }

    @Override
    protected void onHeartbeat() throws Exception {
        sessionRuntime.onTick();
    }

    @Override
    protected void onDisconnected() {
        sessionRuntime.onDisconnected();
    }

    private void startSession() {
        try {
            sessionRuntime.onConnected();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize session for driverCode=" + driverPlugin.driverCode(), e);
        }
    }

    private final class RuntimeProtocolSessionContext implements ProtocolSessionContext {
        private final OutboundCommandSender commandSender = PluginBackedDeviceConnHandler.this::sendCommand;

        @Override
        public DriverContext driverContext() {
            return driverContext;
        }

        @Override
        public ObservationPublisher publisher() {
            return publisher;
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
        public OutboundCommandSender commandSender() {
            return commandSender;
        }

        @Override
        public String remoteIp() {
            return ip;
        }

        @Override
        public void touchHeartbeat() {
            PluginBackedDeviceConnHandler.this.touchHeartbeat();
        }

        @Override
        public void markStale(String reason) {
            log.warn("Mark session stale for driverCode={} ip={}, reason={}",
                driverPlugin.driverCode(), ip, reason);
            setStale();
        }
    }

    private void sendCommand(byte[] bytes, long delayMs) {
        if (bytes == null || bytes.length == 0 || !isAlive) {
            return;
        }

        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            connLock.lock();
            try {
                if (!isAlive) {
                    return;
                }
                ByteBuffer command = ByteBuffer.wrap(bytes);
                socketChannel.write(command);
            } finally {
                connLock.unlock();
            }
        } catch (InterruptedException e) {
            closeWithoutLock(null);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Failed to send plugin command for driverCode={} ip={}, err={}",
                driverPlugin.driverCode(), ip, e.getMessage());
        }
    }

    private final DriverContext driverContext;
    private final DriverPlugin driverPlugin;
    private final ObservationPublisher publisher;
    private final PrometheusMetricService metricService;
    private final TxtDumper txtDumper;
    private final FrameAccumulator frameAccumulator;
    private final ProtocolSessionContext sessionContext;
    private final SessionRuntime sessionRuntime;
}

