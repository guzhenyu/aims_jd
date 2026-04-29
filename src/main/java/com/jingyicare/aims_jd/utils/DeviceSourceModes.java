package com.jingyicare.aims_jd.utils;

import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DeviceInfoPB;

public final class DeviceSourceModes {
    public static final int INBOUND_SERVER = 1;
    public static final int OUTBOUND_CLIENT = 2;
    public static final int NON_DIRECT_CONNECT = 3;

    private DeviceSourceModes() {}

    public static boolean isDirectSource(DeviceInfoPB deviceInfo) {
        return deviceInfo != null && isDirectSource(deviceInfo.getSourceMode());
    }

    public static boolean isDirectSource(int sourceMode) {
        return sourceMode == INBOUND_SERVER || sourceMode == OUTBOUND_CLIENT;
    }

    public static boolean isInboundServer(DeviceInfoPB deviceInfo) {
        return deviceInfo != null && deviceInfo.getSourceMode() == INBOUND_SERVER;
    }

    public static boolean isOutboundClient(DeviceInfoPB deviceInfo) {
        return deviceInfo != null && deviceInfo.getSourceMode() == OUTBOUND_CLIENT;
    }

    public static boolean isIndirectTarget(DeviceInfoPB deviceInfo) {
        return deviceInfo != null && deviceInfo.getSourceMode() == NON_DIRECT_CONNECT;
    }
}

