package com.jingyicare.aims_jd.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.DeviceInfoPB;

@Repository
@Slf4j
public class AimsDeviceRepository {
    public AimsDeviceRepository(
        JdbcTemplate jdbcTemplate,
        @Value("${aims.engine.schema:public}") String engineSchema,
        @Value("${aims.jd.schema:aims_jd}") String jdSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.engineSchema = sanitizeSchema(engineSchema);
        this.jdSchema = sanitizeSchema(jdSchema);
    }

    public List<DeviceInfoPB> syncAndFindActiveDevices(Set<String> supportedDriverCodes) {
        Set<String> drivers = normalizeDrivers(supportedDriverCodes);
        try {
            List<DeviceRow> sourceRows = fetchEngineActiveDeviceRows(drivers);
            upsertSnapshot(sourceRows);
            markMissingSnapshotRowsDeleted(sourceRows);
            log.info("Synced {} AIMS device definitions into {}.device_infos", sourceRows.size(), jdSchema);
        } catch (Exception e) {
            log.error("Failed to sync AIMS device definitions; fallback to local snapshot: {}", e.toString(), e);
        }
        try {
            return findActiveSnapshotDevices(drivers);
        } catch (Exception e) {
            log.error("Failed to load local AIMS JD device snapshot: {}", e.toString(), e);
            return List.of();
        }
    }

    private List<DeviceRow> fetchEngineActiveDeviceRows(Set<String> drivers) {
        String sql = """
            SELECT id, dept_id, device_sn, device_type, device_name, device_ip, device_port,
                   device_driver_code, source_mode, source_topology, upstream_device_id, pds_ip_seq,
                   is_deleted, deleted_by, deleted_at, modified_by, modified_at
            FROM %s.device_infos
            WHERE is_deleted = false
            """.formatted(engineSchema);
        return jdbcTemplate.query(sql, (rs, rowNum) -> toRow(rs)).stream()
            .filter(row -> drivers.isEmpty() || drivers.contains(row.deviceDriverCode()))
            .collect(Collectors.toList());
    }

    private List<DeviceInfoPB> findActiveSnapshotDevices(Set<String> drivers) {
        String sql = """
            SELECT id, dept_id, device_sn, device_type, device_name, device_ip, device_port,
                   device_driver_code, source_mode, source_topology, upstream_device_id, pds_ip_seq,
                   is_deleted, deleted_by, deleted_at, modified_by, modified_at
            FROM %s.device_infos
            WHERE is_deleted = false
            """.formatted(jdSchema);
        return jdbcTemplate.query(sql, (rs, rowNum) -> toRow(rs)).stream()
            .filter(row -> drivers.isEmpty() || drivers.contains(row.deviceDriverCode()))
            .map(AimsDeviceRepository::toPb)
            .collect(Collectors.toList());
    }

    private void upsertSnapshot(List<DeviceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
            INSERT INTO %s.device_infos (
                id, dept_id, device_sn, device_type, device_name, device_ip, device_port,
                device_driver_code, source_mode, source_topology, upstream_device_id, pds_ip_seq,
                is_deleted, deleted_by, deleted_at, modified_by, modified_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                dept_id = EXCLUDED.dept_id,
                device_sn = EXCLUDED.device_sn,
                device_type = EXCLUDED.device_type,
                device_name = EXCLUDED.device_name,
                device_ip = EXCLUDED.device_ip,
                device_port = EXCLUDED.device_port,
                device_driver_code = EXCLUDED.device_driver_code,
                source_mode = EXCLUDED.source_mode,
                source_topology = EXCLUDED.source_topology,
                upstream_device_id = EXCLUDED.upstream_device_id,
                pds_ip_seq = EXCLUDED.pds_ip_seq,
                is_deleted = EXCLUDED.is_deleted,
                deleted_by = EXCLUDED.deleted_by,
                deleted_at = EXCLUDED.deleted_at,
                modified_by = EXCLUDED.modified_by,
                modified_at = EXCLUDED.modified_at
            """.formatted(jdSchema);
        jdbcTemplate.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
            ps.setInt(1, row.id());
            ps.setInt(2, row.deptId());
            ps.setString(3, row.deviceSn());
            ps.setString(4, row.deviceType());
            ps.setString(5, row.deviceName());
            ps.setString(6, row.deviceIp());
            ps.setString(7, row.devicePort());
            ps.setString(8, row.deviceDriverCode());
            ps.setInt(9, row.sourceMode());
            ps.setInt(10, row.sourceTopology());
            ps.setInt(11, row.upstreamDeviceId());
            ps.setInt(12, row.pdsIpSeq());
            ps.setBoolean(13, row.isDeleted());
            setNullableInt(ps, 14, row.deletedBy());
            ps.setObject(15, row.deletedAt());
            setNullableInt(ps, 16, row.modifiedBy());
            ps.setObject(17, row.modifiedAt());
        });
    }

    private void markMissingSnapshotRowsDeleted(List<DeviceRow> activeRows) {
        if (activeRows.isEmpty()) {
            jdbcTemplate.update("UPDATE %s.device_infos SET is_deleted = true WHERE is_deleted = false".formatted(jdSchema));
            return;
        }
        List<Integer> ids = activeRows.stream().map(DeviceRow::id).toList();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(ids);
        jdbcTemplate.update(
            "UPDATE %s.device_infos SET is_deleted = true WHERE is_deleted = false AND id NOT IN (%s)"
                .formatted(jdSchema, placeholders),
            args.toArray()
        );
    }

    private static DeviceRow toRow(ResultSet rs) throws SQLException {
        return new DeviceRow(
            rs.getInt("id"),
            rs.getInt("dept_id"),
            nullToBlank(rs.getString("device_sn")),
            nullToBlank(rs.getString("device_type")),
            nullToBlank(rs.getString("device_name")),
            nullToBlank(rs.getString("device_ip")),
            nullToBlank(rs.getString("device_port")),
            nullToBlank(rs.getString("device_driver_code")),
            rs.getInt("source_mode"),
            rs.getInt("source_topology"),
            rs.getInt("upstream_device_id"),
            rs.getInt("pds_ip_seq"),
            rs.getBoolean("is_deleted"),
            getNullableInt(rs, "deleted_by"),
            rs.getObject("deleted_at", LocalDateTime.class),
            getNullableInt(rs, "modified_by"),
            rs.getObject("modified_at", LocalDateTime.class)
        );
    }

    private static DeviceInfoPB toPb(DeviceRow row) {
        return DeviceInfoPB.newBuilder()
            .setId(row.id())
            .setDeptId(row.deptId())
            .setDeviceSn(row.deviceSn())
            .setDeviceType(row.deviceType())
            .setDeviceName(row.deviceName())
            .setDeviceIp(row.deviceIp())
            .setDevicePort(row.devicePort())
            .setDeviceDriverCode(row.deviceDriverCode())
            .setSourceMode(row.sourceMode())
            .setSourceTopology(row.sourceTopology())
            .setUpstreamDeviceId(row.upstreamDeviceId())
            .setPdsIpSeq(row.pdsIpSeq())
            .build();
    }

    private static Set<String> normalizeDrivers(Set<String> supportedDriverCodes) {
        if (supportedDriverCodes == null || supportedDriverCodes.isEmpty()) {
            return Set.of();
        }
        return supportedDriverCodes.stream()
            .filter(code -> code != null && !code.isBlank())
            .map(String::trim)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static String sanitizeSchema(String schema) {
        String normalized = schema == null || schema.isBlank() ? "public" : schema.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schema);
        }
        return normalized;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableInt(java.sql.PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
            return;
        }
        ps.setInt(index, value);
    }

    private final JdbcTemplate jdbcTemplate;
    private final String engineSchema;
    private final String jdSchema;

    private record DeviceRow(
        int id,
        int deptId,
        String deviceSn,
        String deviceType,
        String deviceName,
        String deviceIp,
        String devicePort,
        String deviceDriverCode,
        int sourceMode,
        int sourceTopology,
        int upstreamDeviceId,
        int pdsIpSeq,
        boolean isDeleted,
        Integer deletedBy,
        LocalDateTime deletedAt,
        Integer modifiedBy,
        LocalDateTime modifiedAt
    ) {}
}

