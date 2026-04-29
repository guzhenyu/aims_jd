package com.jingyicare.aims_jd.driver.frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.FrameDecodeResult;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * ABL90 分帧：
 * ENQ / EOT 为单字节帧；
 * STX 开始的文本帧必须到 CRLF 中的 LF 才算完整。
 */
public final class Abl90FrameDecoder implements FrameDecoder {
    @Override
    public FrameDecodeResult decode(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return FrameDecodeResult.empty(new byte[0]);
        }

        List<Frame> frames = new ArrayList<>();
        int cursor = 0;
        int remainingStart = buffer.length;

        while (cursor < buffer.length) {
            int start = findStart(buffer, cursor);
            if (start < 0) {
                remainingStart = cursor;
                break;
            }

            byte marker = buffer[start];
            if (marker == Consts.ENQ || marker == Consts.EOT) {
                frames.add(new Frame(new byte[] {marker}, System.nanoTime()));
                cursor = start + 1;
                remainingStart = cursor;
                continue;
            }

            int lf = indexOf(buffer, start + 1, Consts.LF);
            if (lf < 0) {
                remainingStart = start;
                break;
            }

            frames.add(new Frame(Arrays.copyOfRange(buffer, start, lf + 1), System.nanoTime()));
            cursor = lf + 1;
            remainingStart = cursor;
        }

        byte[] remaining = remainingStart >= buffer.length
            ? new byte[0]
            : Arrays.copyOfRange(buffer, remainingStart, buffer.length);
        return new FrameDecodeResult(frames, remaining);
    }

    @Override
    public Optional<byte[]> softTrim(byte[] buffer) {
        int split = findLast(buffer, Consts.LF);
        if (split >= 0) {
            return Optional.of(Arrays.copyOfRange(buffer, split + 1, buffer.length));
        }

        split = findLast(buffer, Consts.EOT);
        if (split >= 0) {
            return Optional.of(Arrays.copyOfRange(buffer, split + 1, buffer.length));
        }

        split = findLast(buffer, Consts.ENQ);
        if (split >= 0) {
            return Optional.of(Arrays.copyOfRange(buffer, split + 1, buffer.length));
        }
        return Optional.empty();
    }

    private static int findStart(byte[] data, int from) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == Consts.ENQ || data[i] == Consts.EOT || data[i] == Consts.STX) {
                return i;
            }
        }
        return -1;
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

