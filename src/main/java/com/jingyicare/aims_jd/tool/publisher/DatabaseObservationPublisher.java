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
        @Value("${aims.jd.schema:aims_jd}") String jdSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdSchema = sanitizeSchema(jdSchema);
    }

    @Override
    public void publishDeviceData(ObservationBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        int deptId = parseDeptId(batch.attributes().get(ATTR_DEPARTMENT_ID));
        if (deptId <= 0 || batch.deviceId() <= 0) {
            log.warn("Skip device data with invalid device/dept: deviceId={} deptId={}",
                batch.deviceId(), deptId);
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
            """.formatted(jdSchema, jdSchema);

        int inserted = 0;
        for (Row row : rows) {
            LocalDateTime nextMinute = row.recordedAt().plusMinutes(1);
            try {
                inserted += jdbcTemplate.update(
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
            } catch (Exception e) {
                log.error("Failed to insert device data: deviceId={} paramCode={} recordedStr={} err={}",
                    row.deviceId(), row.paramCode(), row.recordedStr(), e.toString(), e);
            }
        }

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
        String normalized = schema == null || schema.isBlank() ? "aims_jd" : schema.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schema);
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private final JdbcTemplate jdbcTemplate;
    private final String jdSchema;

    private record Row(
        int deptId,
        int deviceId,
        String paramCode,
        LocalDateTime recordedAt,
        String recordedStr
    ) {}
}

