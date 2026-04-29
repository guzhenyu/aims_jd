package com.jingyicare.aims_jd.driver.registry;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;

/**
 * 用于替代 DeviceConnFactory 中 if/else 链。
 */
public interface DriverPluginFactory {

    String driverCode();

    DriverPlugin create(DriverContext context);
}

