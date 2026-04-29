package com.jingyicare.aims_jd.driver.registry;

import java.util.Collection;
import java.util.Optional;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;

/**
 * 第一阶段注册中心接口。
 */
public interface DriverRegistry {

    void register(DriverPluginFactory factory);

    Optional<DriverPluginFactory> find(String driverCode);

    Collection<String> driverCodes();

    default DriverPlugin requirePlugin(String driverCode, DriverContext context) {
        DriverPluginFactory factory = find(driverCode)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported driverCode: " + driverCode));
        return factory.create(context);
    }
}

