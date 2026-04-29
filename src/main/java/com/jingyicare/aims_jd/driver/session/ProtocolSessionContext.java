package com.jingyicare.aims_jd.driver.session;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.tool.PrometheusMetricService;
import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;

/**
 * 协议层可见的统一上下文。
 */
public interface ProtocolSessionContext {

    DriverContext driverContext();

    ObservationPublisher publisher();

    PrometheusMetricService metricService();

    TxtDumper txtDumper();

    OutboundCommandSender commandSender();

    String remoteIp();

    void touchHeartbeat();

    void markStale(String reason);

    default void send(byte[] bytes) {
        commandSender().send(bytes, 0L);
    }

    default void send(byte[] bytes, long delayMs) {
        commandSender().send(bytes, delayMs);
    }
}

