package com.jingyicare.aims_jd.driver.frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.FrameDecodeResult;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * SV300 协议分帧：SOH ... CR。
 * payload 保留完整协议帧，包括 SOH 和结尾 CR。
 */
public final class Sv300FrameDecoder implements FrameDecoder {
    @Override
    public FrameDecodeResult decode(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return FrameDecodeResult.empty(new byte[0]);
        }

        List<Frame> frames = new ArrayList<>();
        int cursor = 0;
        int remainingStart = buffer.length;

        while (cursor < buffer.length) {
            int start = indexOf(buffer, cursor, Consts.SOH);
            if (start < 0) {
                remainingStart = cursor;
                break;
            }

            int end = indexOf(buffer, start, Consts.CR);
            if (end < 0) {
                remainingStart = start;
                break;
            }

            frames.add(new Frame(Arrays.copyOfRange(buffer, start, end + 1), System.nanoTime()));
            cursor = end + 1;
            remainingStart = cursor;
        }

        byte[] remaining = remainingStart >= buffer.length
            ? new byte[0]
            : Arrays.copyOfRange(buffer, remainingStart, buffer.length);
        return new FrameDecodeResult(frames, remaining);
    }

    @Override
    public Optional<byte[]> softTrim(byte[] buffer) {
        int split = findLast(buffer, Consts.CR);
        if (split >= 0) {
            return Optional.of(Arrays.copyOfRange(buffer, split + 1, buffer.length));
        }

        split = findLast(buffer, Consts.SOH);
        if (split >= 0) {
            return Optional.of(Arrays.copyOfRange(buffer, split, buffer.length));
        }
        return Optional.empty();
    }

    private static int indexOf(byte[] data, int from, byte value) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int findLast(byte[] data, byte value) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }
}

