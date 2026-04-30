package com.jingyicare.aims_jd.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
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
        @Value("${aims.engine.schema:public}") String engineSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.engineSchema = sanitizeSchema(engineSchema);
    }

    public List<DeviceInfoPB> findActiveDevices(Set<String> supportedDriverCodes) {
        Set<String> drivers = normalizeDrivers(supportedDriverCodes);
        try {
            List<DeviceInfoPB> devices = fetchEngineActiveDeviceRows(drivers).stream()
                .map(AimsDeviceRepository::toPb)
                .collect(Collectors.toList());
            log.info("Loaded {} active AIMS device definitions from {}.device_infos", devices.size(), engineSchema);
            return devices;
        } catch (Exception e) {
            log.error("Failed to load active AIMS device definitions from {}.device_infos: {}",
                engineSchema, e.toString(), e);
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

    private final JdbcTemplate jdbcTemplate;
    private final String engineSchema;

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

