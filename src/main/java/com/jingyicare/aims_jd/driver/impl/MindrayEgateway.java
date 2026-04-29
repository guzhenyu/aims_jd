package com.jingyicare.aims_jd.driver.impl;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.impl.base.AbstractHl7MllpCentralStationDriver;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;

/**
 * Mindray eGateway HL7/MLLP 驱动。
 * 保留旧行为：ORU 消息或任意包含 OBX 的报文都尝试映射，默认不回 ACK。
 */
public class MindrayEgateway extends AbstractHl7MllpCentralStationDriver {
    public static final String DRIVER_CODE = "jd_cms_mindray_egateway";

    public MindrayEgateway(DriverContext context) {
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

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new MindrayEgateway(context);
        }
    }
}

