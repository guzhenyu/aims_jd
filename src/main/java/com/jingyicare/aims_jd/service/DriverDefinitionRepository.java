package com.jingyicare.aims_jd.service;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DevObservationConfigPB;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DeviceInfoPB;

/**
 * 将当前 DeviceConnManager 里“加载 driver txt”的职责单独抽出来。
 */
public interface DriverDefinitionRepository {

    Optional<DevObservationConfigPB> findObservationConfig(String driverCode);

    Map<String, String> findParamMap(String driverCode);

    default DriverContext buildContext(
        String remoteIp,
        DeviceInfoPB deviceInfo,
        Charset charset,
        String zoneId
    ) {
        String driverCode = deviceInfo == null ? "" : deviceInfo.getDeviceDriverCode();
        DevObservationConfigPB config = findObservationConfig(driverCode)
            .orElseThrow(() -> new IllegalArgumentException("No observation config for driverCode: " + driverCode));

        return new DriverContext(
            remoteIp,
            deviceInfo,
            config,
            findParamMap(driverCode),
            config.getObsPageList(),
            charset,
            zoneId
        );
    }
}

