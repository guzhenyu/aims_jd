package com.jingyicare.aims_jd.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jingyicare.aims_jd.utils.TimeUtils;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DeviceDataWriteService {
    public static final String STATUS_INSERTED = "inserted";
    public static final String STATUS_SKIPPED_DUPLICATE = "skipped_duplicate";
    public static final String STATUS_ERROR = "error";

    public DeviceDataWriteService(
        JdbcTemplate jdbcTemplate,
        @Value("${aims.jd.schema:public}") String dataSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSchema = sanitizeSchema(dataSchema);
    }

    public WriteResult write(WriteRequest request) {
        if (request == null) {
            return WriteResult.rejected("request is required");
        }
        if (request.deptId() <= 0 || request.deviceId() <= 0) {
            return WriteResult.rejected(
                "invalid device/dept: device_id=" + request.deviceId() + " dept_id=" + request.deptId()
            );
        }

        LocalDateTime requestedRecordedAt = request.recordedAtUtc() == null
            ? TimeUtils.getNowUtc()
            : request.recordedAtUtc();
        LocalDateTime recordedAt = requestedRecordedAt.truncatedTo(ChronoUnit.MINUTES);

        List<Row> rows = new ArrayList<>();
        for (DeviceDataValue value : request.values()) {
            normalizeValue(value).ifPresent(recordedStr ->
                rows.add(new Row(
                    request.deptId(),
                    request.deviceId(),
                    value.paramCode(),
                    recordedAt,
                    recordedStr
                ))
            );
        }
        if (rows.isEmpty()) {
            return new WriteResult(0, 0, List.of(), "");
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
        int errors = 0;
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
                    affected > 0 ? STATUS_INSERTED : STATUS_SKIPPED_DUPLICATE
                ));
            } catch (Exception e) {
                errors++;
                rowResults.add(new RowInsertResult(row, STATUS_ERROR));
                log.error(
                    "Failed to insert device data: deviceId={} driverCode={} connectionId={} paramCode={} "
                        + "recordedStr={} err={}",
                    row.deviceId(),
                    blankToDash(request.driverCode()),
                    blankToDash(request.connectionId()),
                    row.paramCode(),
                    row.recordedStr(),
                    e.toString(),
                    e
                );
            }
        }

        return new WriteResult(inserted, errors, rowResults, "");
    }

    public static int parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Optional<String> normalizeValue(DeviceDataValue value) {
        if (value == null || isBlank(value.paramCode()) || isBlank(value.recordedStr())) {
            return Optional.empty();
        }
        BigDecimal numeric;
        try {
            numeric = new BigDecimal(value.recordedStr().trim());
        } catch (NumberFormatException e) {
            log.debug("Skip non-numeric device value: paramCode={} value={}",
                value.paramCode(), value.recordedStr());
            return Optional.empty();
        }

        if ("temperature".equals(value.paramCode()) && numeric.compareTo(BigDecimal.valueOf(45)) > 0) {
            numeric = numeric.subtract(BigDecimal.valueOf(32))
                .multiply(BigDecimal.valueOf(5))
                .divide(BigDecimal.valueOf(9), 2, RoundingMode.HALF_UP);
        }
        return Optional.of(numeric.stripTrailingZeros().toPlainString());
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

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private final JdbcTemplate jdbcTemplate;
    private final String dataSchema;

    public record WriteRequest(
        int deptId,
        int deviceId,
        LocalDateTime recordedAtUtc,
        List<DeviceDataValue> values,
        String driverCode,
        String connectionId
    ) {
        public WriteRequest {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record DeviceDataValue(
        String paramCode,
        String recordedStr
    ) {}

    public record Row(
        int deptId,
        int deviceId,
        String paramCode,
        LocalDateTime recordedAt,
        String recordedStr
    ) {}

    public record RowInsertResult(
        Row row,
        String status
    ) {}

    public record WriteResult(
        int insertedCount,
        int errorCount,
        List<RowInsertResult> rowResults,
        String rejectedReason
    ) {
        public WriteResult {
            rowResults = rowResults == null ? List.of() : List.copyOf(rowResults);
            rejectedReason = rejectedReason == null ? "" : rejectedReason;
        }

        public static WriteResult rejected(String reason) {
            return new WriteResult(0, 0, List.of(), reason);
        }

        public boolean rejected() {
            return !rejectedReason.isBlank();
        }

        public int skippedDuplicateCount() {
            int count = 0;
            for (RowInsertResult result : rowResults) {
                if (STATUS_SKIPPED_DUPLICATE.equals(result.status())) {
                    count++;
                }
            }
            return count;
        }
    }
}
