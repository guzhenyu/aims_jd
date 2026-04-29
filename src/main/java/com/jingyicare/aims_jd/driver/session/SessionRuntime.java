package com.jingyicare.aims_jd.driver.session;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jingyicare.aims_jd.driver.frame.FrameAccumulator;
import com.jingyicare.aims_jd.driver.model.Frame;

/**
 * 第一阶段统一运行时骨架。
 * 只负责：收 chunk -> 分帧 -> 推进协议状态机。
 */
public final class SessionRuntime {
    private final FrameAccumulator frameAccumulator;
    private final ProtocolSession protocolSession;
    private final ProtocolSessionContext context;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SessionRuntime(
        FrameAccumulator frameAccumulator,
        ProtocolSession protocolSession,
        ProtocolSessionContext context
    ) {
        this.frameAccumulator = frameAccumulator;
        this.protocolSession = protocolSession;
        this.context = context;
    }

    public void onConnected() throws Exception {
        if (closed.get()) {
            return;
        }
        protocolSession.onConnected(context);
    }

    public void onBytes(byte[] chunk) throws Exception {
        if (closed.get()) {
            return;
        }
        context.touchHeartbeat();
        List<Frame> frames = frameAccumulator.append(chunk);
        for (Frame frame : frames) {
            protocolSession.onFrame(frame, context);
        }
    }

    public void onTick() throws Exception {
        if (closed.get()) {
            return;
        }
        protocolSession.onTick(context);
    }

    public void onDisconnected() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        protocolSession.onDisconnected(context);
    }

    public boolean isClosed() {
        return closed.get();
    }
}

