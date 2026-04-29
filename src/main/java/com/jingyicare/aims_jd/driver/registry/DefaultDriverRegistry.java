package com.jingyicare.aims_jd.driver.registry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最简单的内存注册中心实现。
 */
public class DefaultDriverRegistry implements DriverRegistry {
    private final Map<String, DriverPluginFactory> factories = new ConcurrentHashMap<>();

    @Override
    public void register(DriverPluginFactory factory) {
        if (factory == null || factory.driverCode() == null || factory.driverCode().isBlank()) {
            throw new IllegalArgumentException("DriverPluginFactory.driverCode must not be blank");
        }
        factories.put(factory.driverCode(), factory);
    }

    @Override
    public Optional<DriverPluginFactory> find(String driverCode) {
        return Optional.ofNullable(factories.get(driverCode));
    }

    @Override
    public Collection<String> driverCodes() {
        return factories.keySet();
    }
}

