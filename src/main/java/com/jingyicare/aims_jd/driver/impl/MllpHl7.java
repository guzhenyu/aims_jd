package com.jingyicare.aims_jd.driver.impl;

import java.util.Optional;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.impl.base.AbstractHl7MllpCentralStationDriver;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;

/**
 * Philips Intellivue HL7/MLLP 驱动。
 * 第一阶段迁移后由 DriverPlugin + SessionRuntime 承接。
 */
public class MllpHl7 extends AbstractHl7MllpCentralStationDriver {
    public static final String DRIVER_CODE = "jd_cms_philips_intellivue";
    private static final long ACK_DELAY_MS = 5L;

    public MllpHl7(DriverContext context) {
        super(context);
    }

    @Override
    public String driverCode() {
        return DRIVER_CODE;
    }

    @Override
    protected Optional<byte[]> buildAck(String messageControlId) {
        if (messageControlId == null || messageControlId.isBlank()) {
            return Optional.empty();
        }
        String ackPayload = "MSH|^~\\&|jyicis|jyicis|||||ACK|2|P|2.3"
            + "\r"
            + "MSA|AA|"
            + messageControlId;
        return Optional.of(buildMllpFrame(ackPayload));
    }

    @Override
    protected long ackDelayMs() {
        return ACK_DELAY_MS;
    }

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new MllpHl7(context);
        }
    }
}

