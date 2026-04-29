package com.jingyicare.aims_jd.driver.frame;

import java.util.Arrays;
import java.util.List;

import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.FrameDecodeResult;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * 将网络 chunk 累加成协议帧。
 */
public final class FrameAccumulator {
    private final FrameDecoder decoder;
    private byte[] remaining = new byte[0];

    public FrameAccumulator(FrameDecoder decoder) {
        this.decoder = decoder;
    }

    public synchronized List<Frame> append(byte[] chunk) {
        byte[] merged = guardOverflow(merge(remaining, chunk));
        FrameDecodeResult result = decoder.decode(merged);
        this.remaining = guardOverflow(result.copyRemaining());
        return result.frames();
    }

    public synchronized void reset() {
        this.remaining = new byte[0];
    }

    public synchronized byte[] copyRemaining() {
        return Arrays.copyOf(remaining, remaining.length);
    }

    private static byte[] merge(byte[] left, byte[] right) {
        byte[] a = left == null ? new byte[0] : left;
        byte[] b = right == null ? new byte[0] : right;
        byte[] merged = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    private byte[] guardOverflow(byte[] buffer) {
        byte[] current = buffer == null ? new byte[0] : buffer;
        if (current.length <= Consts.MAX_BUFFER_BYTES) {
            return current;
        }

        byte[] trimmed = null;
        var trimmedCandidate = decoder.softTrim(current);
        if (trimmedCandidate.isPresent()) {
            byte[] candidate = trimmedCandidate.get();
            if (candidate != null && candidate.length > 0 && candidate.length < current.length) {
                trimmed = candidate;
            }
        }
        if (trimmed != null) {
            current = trimmed;
        }

        if (current.length <= Consts.MAX_BUFFER_BYTES) {
            return current;
        }

        int keep = Math.min(Consts.BYTES_LEFT_FOR_TRUNCATE, current.length);
        return Arrays.copyOfRange(current, current.length - keep, current.length);
    }
}

