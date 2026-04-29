package com.jingyicare.aims_jd.driver.model;

/**
 * 中央站场景下的一台逻辑设备/床位。
 */
public record LogicalDeviceRef(
    String logicalKey,
    String bedNumber,
    String upstreamIp
) {}

