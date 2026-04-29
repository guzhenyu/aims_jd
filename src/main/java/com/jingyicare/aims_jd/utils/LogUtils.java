package com.jingyicare.aims_jd.utils;

import org.springframework.context.ConfigurableApplicationContext;

import ch.qos.logback.classic.LoggerContext;
import org.apache.logging.log4j.LogManager;
import org.slf4j.LoggerFactory;

public class LogUtils {
    public static void flushAndQuit(ConfigurableApplicationContext context) {
        try {
            LogManager.shutdown();
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
            Thread.sleep(500);
            context.close();
            System.exit(0);
        } catch (Exception e) {
            System.out.println("Failed to quit gracefully " + e);
            System.exit(1);
        }
    }
}
