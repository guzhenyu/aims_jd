package com.jingyicare.aims_jd.driver.model;

/**
 * 归一化后的单个观测值。
 */
public record ObservationValue(
    String paramCode,
    String recordedStr,
    String recordedAtIso8601
) {}

