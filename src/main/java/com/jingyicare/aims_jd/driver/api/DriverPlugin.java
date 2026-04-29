package com.jingyicare.aims_jd.driver.api;

import com.jingyicare.aims_jd.driver.frame.FrameDecoder;
import com.jingyicare.aims_jd.driver.session.ProtocolSession;

/**
 * 每个具体驱动在运行时对应一个 DriverPlugin 实例。
 * 一个实例只服务一个连接/会话。
 */
public interface DriverPlugin {

    String driverCode();

    default SourceMode sourceMode() {
        return SourceMode.INBOUND_SERVER;
    }

    default SourceTopology sourceTopology() {
        return SourceTopology.SINGLE_DEVICE;
    }

    FrameDecoder frameDecoder();

    ProtocolSession protocolSession();
}

