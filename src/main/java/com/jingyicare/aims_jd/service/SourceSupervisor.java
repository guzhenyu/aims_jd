package com.jingyicare.aims_jd.service;

import java.util.Collection;
import java.util.Optional;

import com.jingyicare.aims_jd.driver.session.SessionRuntime;

/**
 * 连接/数据源生命周期管理器。
 * 第一阶段可以先只服务现有入站 TCP 连接。
 */
public interface SourceSupervisor {

    void register(String sourceKey, SessionRuntime runtime);

    Optional<SessionRuntime> find(String sourceKey);

    void remove(String sourceKey);

    Collection<String> keys();

    void tickAll();

    void closeAll();
}

