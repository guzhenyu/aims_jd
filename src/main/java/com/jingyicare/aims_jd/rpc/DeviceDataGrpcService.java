package com.jingyicare.aims_jd.rpc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.aims_jd.proto.rpc.AddDeviceDataReq;
import com.jingyicare.aims_jd.proto.rpc.AddDeviceDataResp;
import com.jingyicare.aims_jd.proto.rpc.AtomicDeviceDataPB;
import com.jingyicare.aims_jd.proto.rpc.DeviceDataServiceGrpc;
import com.jingyicare.aims_jd.service.DeviceDataWriteService;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.DeviceDataValue;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteRequest;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteResult;
import com.jingyicare.aims_jd.utils.TimeUtils;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DeviceDataGrpcService extends DeviceDataServiceGrpc.DeviceDataServiceImplBase {
    public static final int CODE_OK = 0;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_WRITE_FAILED = 500;

    public DeviceDataGrpcService(DeviceDataWriteService deviceDataWriteService) {
        this.deviceDataWriteService = deviceDataWriteService;
    }

    @Override
    public void addDeviceData(
        AddDeviceDataReq request,
        StreamObserver<AddDeviceDataResp> responseObserver
    ) {
        try {
            AddDeviceDataResp response = handleAddDeviceData(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Unhandled AddDeviceData error", e);
            responseObserver.onNext(resp(CODE_WRITE_FAILED, "failed to write device data: " + e.getMessage()));
            responseObserver.onCompleted();
        }
    }

    AddDeviceDataResp handleAddDeviceData(AddDeviceDataReq request) {
        if (request == null) {
            return resp(CODE_BAD_REQUEST, "request is required");
        }
        if (request.getDeviceId() <= 0) {
            return resp(CODE_BAD_REQUEST, "device_id must be a positive int32");
        }

        int deptId = DeviceDataWriteService.parsePositiveInt(request.getDeptCode());
        if (deptId <= 0) {
            return resp(CODE_BAD_REQUEST, "dept_code must be a positive integer string");
        }

        LocalDateTime recordedAtUtc = parseRecordedAtUtc(request.getRecordedAtIso8601());
        if (recordedAtUtc == null) {
            return resp(CODE_BAD_REQUEST, "recorded_at_iso8601 is invalid");
        }

        List<DeviceDataValue> values = new ArrayList<>();
        for (AtomicDeviceDataPB item : request.getDataList()) {
            values.add(new DeviceDataValue(item.getParamCode(), item.getRecordedStr()));
        }

        WriteResult result = deviceDataWriteService.write(new WriteRequest(
            deptId,
            request.getDeviceId(),
            recordedAtUtc,
            values,
            request.getDriverCode(),
            request.getConnectionId()
        ));
        if (result.rejected()) {
            return resp(CODE_BAD_REQUEST, result.rejectedReason());
        }
        if (result.errorCount() > 0) {
            return resp(
                CODE_WRITE_FAILED,
                "failed to insert device_data rows: errors=" + result.errorCount()
                    + " inserted=" + result.insertedCount()
            );
        }

        int filtered = Math.max(0, request.getDataCount() - result.rowResults().size());
        return resp(
            CODE_OK,
            "ok inserted=" + result.insertedCount()
                + " duplicate=" + result.skippedDuplicateCount()
                + " filtered=" + filtered
        );
    }

    private static LocalDateTime parseRecordedAtUtc(String recordedAtIso8601) {
        if (recordedAtIso8601 == null || recordedAtIso8601.isBlank()) {
            return TimeUtils.getNowUtc();
        }

        String normalized = recordedAtIso8601.trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Try the broader ISO_DATE_TIME parsers below.
        }

        try {
            return LocalDateTime.ofInstant(
                OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_DATE_TIME).toInstant(),
                ZoneOffset.UTC
            );
        } catch (DateTimeParseException ignored) {
            // Try zoned date-time with region ids such as Asia/Shanghai.
        }

        try {
            return LocalDateTime.ofInstant(
                ZonedDateTime.parse(normalized, DateTimeFormatter.ISO_DATE_TIME).toInstant(),
                ZoneOffset.UTC
            );
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static AddDeviceDataResp resp(int code, String message) {
        return AddDeviceDataResp.newBuilder()
            .setCode(code)
            .setMessage(message == null ? "" : message)
            .build();
    }

    private final DeviceDataWriteService deviceDataWriteService;
}
