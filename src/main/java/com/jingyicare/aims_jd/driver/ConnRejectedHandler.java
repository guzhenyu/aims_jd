package com.jingyicare.aims_jd.driver;

import java.util.concurrent.*;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConnRejectedHandler implements RejectedExecutionHandler {
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        // 向client回写拒绝消息
        if (r instanceof ConnRunnable) {
            ConnRunnable task = (ConnRunnable) r;
            task.sendRejectAck();
            log.warn("Failed to handle device connection task");
        } else {
            log.warn("Rejected task is not an instance of ConnRunnable");
        }
    }
}
