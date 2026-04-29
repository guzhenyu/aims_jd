package com.jingyicare.aims_jd.driver.api;

/**
 * 一个连接在业务上承载的拓扑形态。
 */
public enum SourceTopology {
    /**
     * 一个连接对应一个逻辑设备。
     */
    SINGLE_DEVICE,

    /**
     * 一个连接对应一个中央站，中央站再扇出多个床位/监护仪。
     */
    CENTRAL_STATION_FANOUT
}

