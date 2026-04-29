package com.jingyicare.aims_jd.driver.impl.base;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.model.BgaBatch;
import com.jingyicare.aims_jd.driver.model.ObservationBatch;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;
import com.jingyicare.aims_jd.utils.TimeUtils;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DevObsPagePB;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DevObservationItemPB;

/**
 * 单设备入站协议的轻量级公共基类。
 * 只承载 DriverContext、page map、UTC 时间和 Observation/BGA 构造 helper。
 */
public abstract class AbstractSingleDeviceProtocolDriver implements DriverPlugin {
    protected AbstractSingleDeviceProtocolDriver(DriverContext context) {
        this.context = context;
        this.obsPageItemMap = new HashMap<>();
        for (DevObsPagePB page : context.observationPages()) {
            Map<String, String> itemMap = new HashMap<>();
            for (DevObservationItemPB item : page.getItemList()) {
                itemMap.put(item.getObCode(), item.getMonitoringParamCode());
            }
            obsPageItemMap.put(page.getPageName(), Map.copyOf(itemMap));
        }
    }

    protected String nowIso8601Utc() {
        return TimeUtils.toIso8601String(TimeUtils.getNowUtc(), "UTC");
    }

    protected ObservationBatch newObservationBatch(List<ObservationValue> values, String rawMessage) {
        return new ObservationBatch(
            context.deviceId(),
            context.deviceType(),
            "",
            values,
            defaultObservationAttributes(),
            rawMessage
        );
    }

    protected BgaBatch newBgaBatch(
        String mrnOrBednum,
        int bgaCategoryId,
        String effectiveTimeIso8601,
        Map<String, String> details,
        String rawMessage
    ) {
        return new BgaBatch(mrnOrBednum, bgaCategoryId, effectiveTimeIso8601, details, rawMessage);
    }

    protected Map<String, String> obsPageItemMap(String pageName) {
        return obsPageItemMap.getOrDefault(pageName, Map.of());
    }

    protected void publishObservationBatch(ProtocolSessionContext sessionContext, List<ObservationValue> values, String rawMessage) {
        ObservationBatch batch = newObservationBatch(values, rawMessage);
        if (!batch.isEmpty()) {
            sessionContext.publisher().publishDeviceData(batch);
        }
    }

    protected void publishBgaBatch(ProtocolSessionContext sessionContext, BgaBatch batch) {
        if (batch != null && !batch.isEmpty()) {
            sessionContext.publisher().publishBga(batch);
        }
    }

    protected void dumpText(ProtocolSessionContext sessionContext, String text) {
        if (sessionContext != null && text != null && !text.isBlank()) {
            sessionContext.txtDumper().dump(text);
        }
    }

    protected void dumpDebug(ProtocolSessionContext sessionContext, String text) {
        if (sessionContext != null && text != null && !text.isBlank()) {
            sessionContext.txtDumper().dump(">>> " + text + "\n");
        }
    }

    protected final DriverContext context;

    private final Map<String, Map<String, String>> obsPageItemMap;

    private Map<String, String> defaultObservationAttributes() {
        if (context.departmentId() == null || context.departmentId().isBlank()) {
            return Map.of();
        }
        return Map.of(ObservationPublisher.ATTR_DEPARTMENT_ID, context.departmentId());
    }
}

