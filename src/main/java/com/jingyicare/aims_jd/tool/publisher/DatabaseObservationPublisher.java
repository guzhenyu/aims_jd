package com.jingyicare.aims_jd.tool.publisher;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.model.BgaBatch;
import com.jingyicare.aims_jd.driver.model.ObservationBatch;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.service.DeviceDataWriteService;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.DeviceDataValue;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.Row;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.RowInsertResult;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteRequest;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteResult;
import com.jingyicare.aims_jd.utils.TimeUtils;

@Component
@Slf4j
public class DatabaseObservationPublisher implements ObservationPublisher {
    public DatabaseObservationPublisher(DeviceDataWriteService deviceDataWriteService) {
        this.deviceDataWriteService = deviceDataWriteService;
    }

    @Override
    public void publishDeviceData(ObservationBatch batch) {
        if (batch == null) {
            return;
        }
        if (batch.isEmpty()) {
            logReceivedAndDeviceData(batch, "[]");
            return;
        }

        int deptId = DeviceDataWriteService.parsePositiveInt(batch.attributes().get(ATTR_DEPARTMENT_ID));
        if (deptId <= 0 || batch.deviceId() <= 0) {
            log.warn("Skip device data with invalid device/dept: deviceId={} deptId={}",
                batch.deviceId(), deptId);
            logReceivedAndDeviceData(batch,
                "skipped_invalid_device_or_dept {device_id=" + batch.deviceId() + ", dept_id=" + deptId + "}");
            return;
        }

        List<DeviceDataValue> values = new ArrayList<>();
        for (ObservationValue value : batch.values()) {
            if (value != null) {
                values.add(new DeviceDataValue(value.paramCode(), value.recordedStr()));
            }
        }
        WriteResult result = deviceDataWriteService.write(new WriteRequest(
            deptId,
            batch.deviceId(),
            TimeUtils.getNowUtc(),
            values,
            batch.deviceType(),
            ""
        ));
        if (result.rejected()) {
            log.warn("Skip device data write: {}", result.rejectedReason());
            logReceivedAndDeviceData(batch, "skipped_invalid_device_or_dept {" + result.rejectedReason() + "}");
            return;
        }
        if (result.rowResults().isEmpty()) {
            logReceivedAndDeviceData(batch, "[]");
            return;
        }

        logReceivedAndDeviceData(batch, formatRowResults(result.rowResults()));

        if (result.insertedCount() > 0) {
            Row first = result.rowResults().get(0).row();
            log.info("Inserted device data: deviceId={} count={} firstParamCode={} firstRecordedStr={}",
                batch.deviceId(), result.insertedCount(), first.paramCode(), first.recordedStr());
        }
    }

    @Override
    public void publishBga(BgaBatch batch) {
        log.debug("Ignore BGA batch in AIMS JD");
    }

    private void logReceivedAndDeviceData(ObservationBatch batch, String deviceDataText) {
        log.info("received data(filtered / unfiltered):\nfiltered:\n{}\nunfiltered:\n{}\ndevice_data:\n{}",
            formatObservationValues(batch.values()),
            formatRawMessage(batch.rawMessage()),
            deviceDataText == null || deviceDataText.isBlank() ? "[]" : deviceDataText);
    }

    private static String formatObservationValues(List<ObservationValue> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (ObservationValue value : values) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("{param_code=").append(blankToDash(value.paramCode()))
                .append(", recorded_str=").append(blankToDash(value.recordedStr()))
                .append(", recorded_at=").append(blankToDash(value.recordedAtIso8601()))
                .append('}');
        }
        return builder.toString();
    }

    private static String formatRawMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "[]";
        }
        return rawMessage
            .replace("\r\n", "\n")
            .replace('\r', '\n');
    }

    private static String formatRowResults(List<RowInsertResult> results) {
        if (results == null || results.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (RowInsertResult result : results) {
            Row row = result.row();
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("{dept_id=").append(row.deptId())
                .append(", device_id=").append(row.deviceId())
                .append(", param_code=").append(blankToDash(row.paramCode()))
                .append(", recorded_at=").append(row.recordedAt())
                .append(", recorded_str=").append(blankToDash(row.recordedStr()))
                .append(", status=").append(blankToDash(result.status()))
                .append('}');
        }
        return builder.toString();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private final DeviceDataWriteService deviceDataWriteService;
}

