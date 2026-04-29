package com.jingyicare.aims_jd.driver.model;

import java.util.Arrays;
import java.util.List;

/**
 * 一次分帧后的结果。
 */
public record FrameDecodeResult(List<Frame> frames, byte[] remaining) {
    public FrameDecodeResult {
        frames = frames == null ? List.of() : List.copyOf(frames);
        remaining = remaining == null ? new byte[0] : Arrays.copyOf(remaining, remaining.length);
    }

    public static FrameDecodeResult empty(byte[] remaining) {
        return new FrameDecodeResult(List.of(), remaining);
    }

    public byte[] copyRemaining() {
        return Arrays.copyOf(remaining, remaining.length);
    }
}

