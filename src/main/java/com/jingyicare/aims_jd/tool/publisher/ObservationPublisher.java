package com.jingyicare.aims_jd.tool.publisher;

import com.jingyicare.aims_jd.driver.model.BgaBatch;
import com.jingyicare.aims_jd.driver.model.ObservationBatch;

/**
 * 驱动统一发布出口。
 * 第一阶段只抽象接口，不强制改成异步。
 */
public interface ObservationPublisher {
    String ATTR_DEPARTMENT_ID = "department_id";

    void publishDeviceData(ObservationBatch batch);

    void publishBga(BgaBatch batch);
}

