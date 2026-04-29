package com.jingyicare.aims_jd.driver.model;

import java.util.List;
import java.util.Map;

/**
 * 归一化后的监护/呼吸机等设备数据。
 */
public record ObservationBatch(
    int deviceId,
    String deviceType,
    String deviceBedNumber,
    List<ObservationValue> values,
    Map<String, String> attributes,
    String rawMessage
) {
    public ObservationBatch {
        values = values == null ? List.of() : List.copyOf(values);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}

