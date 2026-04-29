package com.jingyicare.aims_jd.driver.api;

/**
 * 数据源连接方向。
 * 第一阶段先保留现有 INBOUND_SERVER 模式，
 * 但要为未来中央站主动连接模式预留 OUTBOUND_CLIENT。
 */
public enum SourceMode {
    INBOUND_SERVER,
    OUTBOUND_CLIENT,
    NON_DIRECT_CONNECT
}

