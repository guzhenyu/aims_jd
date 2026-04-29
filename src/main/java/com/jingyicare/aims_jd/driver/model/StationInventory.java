package com.jingyicare.aims_jd.driver.model;

import java.util.List;
import java.util.Map;

/**
 * 中央站发现结果。
 */
public record StationInventory(
    List<LogicalDeviceRef> logicalDevices,
    Map<String, String> attributes
) {
    public StationInventory {
        logicalDevices = logicalDevices == null ? List.of() : List.copyOf(logicalDevices);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static StationInventory empty() {
        return new StationInventory(List.of(), Map.of());
    }
}

