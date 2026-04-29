package com.jingyicare.aims_jd.driver;

import java.nio.channels.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConnRunnable implements Runnable {
    public final DeviceConnHandler handler;
    public final DeviceConnHandler.OpType opType;
    public final SelectionKey readableKey;

    public ConnRunnable(
        DeviceConnHandler handler,
        DeviceConnHandler.OpType opType,
        SelectionKey readableKey
    ) {
        this.handler = handler;
        this.opType = opType;
        this.readableKey = readableKey;
    }

    @Override
    public void run() {
        if (opType == DeviceConnHandler.OpType.HANDLE_READ) {
            handler.extractMessages(readableKey);
        } else if (opType == DeviceConnHandler.OpType.HANDLE_HEARTBEAT) {
            handler.sendHeartbeat();
        } else if (opType == DeviceConnHandler.OpType.HANDLE_CLOSE) {
            handler.close(readableKey);
        }
    }

    public void sendRejectAck() {
        // 预留：向设备连接回写拒绝消息的相关代码
    }
}
