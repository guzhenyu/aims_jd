package com.jingyicare.aims_jd.driver.model;

import java.util.Map;

/**
 * 归一化后的血气结果。
 */
public record BgaBatch(
    String mrnOrBednum,
    int bgaCategoryId,
    String effectiveTimeIso8601,
    Map<String, String> details,
    String rawMessage
) {
    public BgaBatch {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public boolean isEmpty() {
        return details.isEmpty();
    }
}

