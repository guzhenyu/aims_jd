package com.jingyicare.aims_jd.driver.session;

import com.jingyicare.aims_jd.driver.model.Frame;

/**
 * 协议状态机。
 */
public interface ProtocolSession {

    default void onConnected(ProtocolSessionContext context) throws Exception {
        // no-op
    }

    void onFrame(Frame frame, ProtocolSessionContext context) throws Exception;

    default void onTick(ProtocolSessionContext context) throws Exception {
        // no-op
    }

    default void onDisconnected(ProtocolSessionContext context) {
        // no-op
    }
}

