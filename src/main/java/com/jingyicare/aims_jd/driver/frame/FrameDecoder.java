package com.jingyicare.aims_jd.driver.frame;

import java.util.Optional;

import com.jingyicare.aims_jd.driver.model.FrameDecodeResult;

/**
 * 流式分帧器：输入当前缓冲区字节，返回完整帧 + 剩余尾巴。
 */
public interface FrameDecoder {

    FrameDecodeResult decode(byte[] buffer);

    default Optional<byte[]> softTrim(byte[] buffer) {
        return Optional.empty();
    }

    default String name() {
        return getClass().getSimpleName();
    }
}

