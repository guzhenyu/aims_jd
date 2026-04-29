package com.jingyicare.aims_jd.driver.frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.FrameDecodeResult;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * 标准 MLLP 分帧：VT ... FS CR
 */
public final class MllpFrameDecoder implements FrameDecoder {

    @Override
    public FrameDecodeResult decode(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return FrameDecodeResult.empty(new byte[0]);
        }

        List<Frame> frames = new ArrayList<>();
        int cursor = 0;
        int remainingStart = buffer.length;

        while (cursor < buffer.length) {
            int vt = indexOf(buffer, cursor, Consts.VT);
            if (vt < 0) {
                remainingStart = buffer.length;
                break;
            }

            int fs = findFsCr(buffer, vt + 1);
            if (fs < 0) {
                remainingStart = vt;
                break;
            }

            byte[] payload = Arrays.copyOfRange(buffer, vt + 1, fs);
            frames.add(new Frame(payload, System.nanoTime()));

            cursor = fs + 2;
            remainingStart = cursor;
        }

        byte[] remaining = remainingStart >= buffer.length
            ? new byte[0]
            : Arrays.copyOfRange(buffer, remainingStart, buffer.length);

        return new FrameDecodeResult(frames, remaining);
    }

    private static int indexOf(byte[] data, int from, byte value) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int findFsCr(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 1 < data.length; i++) {
            if (data[i] == Consts.FS && data[i + 1] == Consts.CR) {
                return i;
            }
        }
        return -1;
    }
}

