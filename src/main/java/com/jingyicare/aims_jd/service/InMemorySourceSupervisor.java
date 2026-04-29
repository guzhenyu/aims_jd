package com.jingyicare.aims_jd.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jingyicare.aims_jd.driver.session.SessionRuntime;

/**
 * 最简单的内存实现，便于先替换 DeviceConnManager 内部 map。
 */
public class InMemorySourceSupervisor implements SourceSupervisor {
    private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public void register(String sourceKey, SessionRuntime runtime) {
        runtimes.put(sourceKey, runtime);
    }

    @Override
    public Optional<SessionRuntime> find(String sourceKey) {
        return Optional.ofNullable(runtimes.get(sourceKey));
    }

    @Override
    public void remove(String sourceKey) {
        runtimes.remove(sourceKey);
    }

    @Override
    public Collection<String> keys() {
        return runtimes.keySet();
    }

    @Override
    public void tickAll() {
        for (SessionRuntime runtime : runtimes.values()) {
            try {
                runtime.onTick();
            } catch (Exception ignored) {
                // 第一阶段骨架先留空，后续由上层接 metrics / close 策略
            }
        }
    }

    @Override
    public void closeAll() {
        for (SessionRuntime runtime : runtimes.values()) {
            runtime.onDisconnected();
        }
        runtimes.clear();
    }
}

