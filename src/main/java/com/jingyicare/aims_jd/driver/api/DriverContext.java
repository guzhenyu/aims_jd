package com.jingyicare.aims_jd.driver.api;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DevObsPagePB;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DevObservationConfigPB;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DeviceInfoPB;

/**
 * 单个驱动会话上下文。
 * 单个设备连接、驱动配置和参数映射的运行时上下文。
 */
public record DriverContext(
    String remoteIp,
    DeviceInfoPB deviceInfo,
    DevObservationConfigPB observationConfig,
    Map<String, String> paramMap,
    List<DevObsPagePB> observationPages,
    Charset charset,
    String zoneId,
    Map<String, String> options
) {
    public DriverContext(
        String remoteIp,
        DeviceInfoPB deviceInfo,
        DevObservationConfigPB observationConfig,
        Map<String, String> paramMap,
        List<DevObsPagePB> observationPages,
        Charset charset,
        String zoneId
    ) {
        this(remoteIp, deviceInfo, observationConfig, paramMap, observationPages, charset, zoneId, Map.of());
    }

    public DriverContext {
        paramMap = paramMap == null ? Map.of() : Map.copyOf(paramMap);
        observationPages = observationPages == null ? List.of() : List.copyOf(observationPages);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public int deviceId() {
        return deviceInfo == null ? 0 : deviceInfo.getId();
    }

    public String deviceType() {
        return deviceInfo == null ? "" : deviceInfo.getDeviceType();
    }

    public String departmentId() {
        return deviceInfo == null ? "" : String.valueOf(deviceInfo.getDeptId());
    }

    public String deviceBedNumber() {
        return "";
    }

    public int upstreamDeviceId() {
        return deviceInfo == null ? 0 : deviceInfo.getUpstreamDeviceId();
    }

    public int pdsIpSeq() {
        return deviceInfo == null ? 0 : deviceInfo.getPdsIpSeq();
    }

    public String driverCode() {
        return deviceInfo == null ? "" : deviceInfo.getDeviceDriverCode();
    }

    public String option(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        String value = options.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public boolean optionBoolean(String key, boolean defaultValue) {
        String value = option(key, "");
        return value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }
}

