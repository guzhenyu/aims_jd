package com.jingyicare.aims_jd.driver.model;

import java.util.Arrays;

/**
 * 完整协议帧。
 */
public record Frame(byte[] payload, long receivedAtNanos) {
    public Frame {
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public int size() {
        return payload.length;
    }

    public byte[] copyPayload() {
        return Arrays.copyOf(payload, payload.length);
    }
}

