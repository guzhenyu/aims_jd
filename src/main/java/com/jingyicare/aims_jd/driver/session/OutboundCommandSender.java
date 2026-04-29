package com.jingyicare.aims_jd.driver.session;

/**
 * 协议层发命令时使用的统一出口。
 */
@FunctionalInterface
public interface OutboundCommandSender {

    void send(byte[] bytes, long delayMs);
}

