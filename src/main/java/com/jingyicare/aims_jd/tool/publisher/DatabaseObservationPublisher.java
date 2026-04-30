package com.jingyicare.aims_jd.tool.publisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.model.BgaBatch;
import com.jingyicare.aims_jd.driver.model.ObservationBatch;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.utils.TimeUtils;

@Component
@Slf4j
public class DatabaseObservationPublisher implements ObservationPublisher {
    public DatabaseObservationPublisher(
        JdbcTemplate jdbcTemplate,
        @Value("${aims.jd.schema:public}") String dataSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSchema = sanitizeSchema(dataSchema);
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

        int deptId = parseDeptId(batch.attributes().get(ATTR_DEPARTMENT_ID));
        if (deptId <= 0 || batch.deviceId() <= 0) {
            log.warn("Skip device data with invalid device/dept: deviceId={} deptId={}",
                batch.deviceId(), deptId);
            logReceivedAndDeviceData(batch,
                "skipped_invalid_device_or_dept {device_id=" + batch.deviceId() + ", dept_id=" + deptId + "}");
            return;
        }

        LocalDateTime recordedAt = TimeUtils.getNowUtc().truncatedTo(ChronoUnit.MINUTES);
        List<Row> rows = new ArrayList<>();
        for (ObservationValue value : batch.values()) {
            normalizeValue(value).ifPresent(recordedStr ->
                rows.add(new Row(deptId, batch.deviceId(), value.paramCode(), recordedAt, recordedStr))
            );
        }
        if (rows.isEmpty()) {
            logReceivedAndDeviceData(batch, "[]");
            return;
        }

        String insertSql = """
            INSERT INTO %s.device_data (dept_id, device_id, param_code, recorded_at, recorded_str)
            SELECT ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1
                FROM %s.device_data
                WHERE device_id = ?
                  AND param_code = ?
                  AND recorded_at >= ?
                  AND recorded_at < ?
            )
            """.formatted(dataSchema, dataSchema);

        int inserted = 0;
        List<RowInsertResult> rowResults = new ArrayList<>();
        for (Row row : rows) {
            LocalDateTime nextMinute = row.recordedAt().plusMinutes(1);
            try {
                int affected = jdbcTemplate.update(
                    insertSql,
                    row.deptId(),
                    row.deviceId(),
                    row.paramCode(),
                    Timestamp.valueOf(row.recordedAt()),
                    row.recordedStr(),
                    row.deviceId(),
                    row.paramCode(),
                    Timestamp.valueOf(row.recordedAt()),
                    Timestamp.valueOf(nextMinute)
                );
                inserted += affected;
                rowResults.add(new RowInsertResult(
                    row,
                    affected > 0 ? "inserted" : "skipped_duplicate"
                ));
            } catch (Exception e) {
                rowResults.add(new RowInsertResult(row, "error"));
                log.error("Failed to insert device data: deviceId={} paramCode={} recordedStr={} err={}",
                    row.deviceId(), row.paramCode(), row.recordedStr(), e.toString(), e);
            }
        }

        logReceivedAndDeviceData(batch, formatRowResults(rowResults));

        if (inserted > 0) {
            Row first = rows.get(0);
            log.info("Inserted device data: deviceId={} count={} firstParamCode={} firstRecordedStr={}",
                batch.deviceId(), inserted, first.paramCode(), first.recordedStr());
        }
    }

    @Override
    public void publishBga(BgaBatch batch) {
        log.debug("Ignore BGA batch in AIMS JD");
    }

    private java.util.Optional<String> normalizeValue(ObservationValue value) {
        if (value == null || isBlank(value.paramCode()) || isBlank(value.recordedStr())) {
            return java.util.Optional.empty();
        }
        BigDecimal numeric;
        try {
            numeric = new BigDecimal(value.recordedStr().trim());
        } catch (NumberFormatException e) {
            log.debug("Skip non-numeric device value: paramCode={} value={}",
                value.paramCode(), value.recordedStr());
            return java.util.Optional.empty();
        }

        if ("temperature".equals(value.paramCode()) && numeric.compareTo(BigDecimal.valueOf(45)) > 0) {
            numeric = numeric.subtract(BigDecimal.valueOf(32))
                .multiply(BigDecimal.valueOf(5))
                .divide(BigDecimal.valueOf(9), 2, RoundingMode.HALF_UP);
        }
        return java.util.Optional.of(numeric.stripTrailingZeros().toPlainString());
    }

    private static int parseDeptId(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(departmentId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sanitizeSchema(String schema) {
        String normalized = schema == null || schema.isBlank() ? "public" : schema.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schema);
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private final JdbcTemplate jdbcTemplate;
    private final String dataSchema;

    private record Row(
        int deptId,
        int deviceId,
        String paramCode,
        LocalDateTime recordedAt,
        String recordedStr
    ) {}

    private record RowInsertResult(
        Row row,
        String status
    ) {}
}

