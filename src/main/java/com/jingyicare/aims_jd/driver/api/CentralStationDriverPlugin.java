package com.jingyicare.aims_jd.driver.api;

import java.util.Optional;

import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.LogicalDeviceRef;
import com.jingyicare.aims_jd.driver.model.StationInventory;

/**
 * 为未来“中央站驱动”预留的扩展接口。
 * 第一阶段不要求接入 PDS，只要求接口占位清晰。
 */
public interface CentralStationDriverPlugin extends DriverPlugin {

    @Override
    default SourceTopology sourceTopology() {
        return SourceTopology.CENTRAL_STATION_FANOUT;
    }

    /**
     * 可选的床位/逻辑设备发现步骤。
     */
    default StationInventory discover() {
        return StationInventory.empty();
    }

    /**
     * 将一帧原始消息归属到某个逻辑设备（床位/监护仪）。
     */
    default Optional<LogicalDeviceRef> resolveLogicalDevice(Frame frame) {
        return Optional.empty();
    }
}

