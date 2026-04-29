package com.jingyicare.aims_jd.driver.impl;

import java.util.Optional;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.impl.base.AbstractHl7MllpCentralStationDriver;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;

public class MindrayN12Monitor extends AbstractHl7MllpCentralStationDriver {
    public static final String DRIVER_CODE = "JY_monitor_mindray_n";

    public MindrayN12Monitor(DriverContext context) {
        super(context);
    }

    @Override
    public String driverCode() {
        return DRIVER_CODE;
    }

    @Override
    protected boolean shouldProcessMessage(String messageType, String er7) {
        return (messageType != null && messageType.startsWith("ORU")) || hasObxSegment(er7);
    }

    @Override
    protected Optional<byte[]> buildAck(String messageControlId) {
        if (messageControlId == null || messageControlId.isBlank()) {
            return Optional.empty();
        }
        String ackPayload = "MSH|^~\\&|aims_jd|aims_jd|||||ACK|"
            + messageControlId
            + "|P|2.3\rMSA|AA|"
            + messageControlId
            + "\r";
        return Optional.of(buildMllpFrame(ackPayload));
    }

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new MindrayN12Monitor(context);
        }
    }
}

