package com.jingyicare.aims_jd.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jingyicare.aims_jd.proto.rpc.AddDeviceDataReq;
import com.jingyicare.aims_jd.proto.rpc.AddDeviceDataResp;
import com.jingyicare.aims_jd.proto.rpc.AtomicDeviceDataPB;
import com.jingyicare.aims_jd.service.DeviceDataWriteService;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.Row;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.RowInsertResult;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteRequest;
import com.jingyicare.aims_jd.service.DeviceDataWriteService.WriteResult;

@ExtendWith(MockitoExtension.class)
class DeviceDataGrpcServiceTest {
    @Test
    void addDeviceDataConvertsDeptCodeToDeptId() {
        Row row = new Row(12, 34, "heart_rate", LocalDateTime.of(2026, 7, 3, 4, 5), "80");
        when(deviceDataWriteService.write(any())).thenReturn(new WriteResult(
            1,
            0,
            List.of(new RowInsertResult(row, DeviceDataWriteService.STATUS_INSERTED)),
            ""
        ));

        DeviceDataGrpcService service = new DeviceDataGrpcService(deviceDataWriteService);
        AddDeviceDataResp response = service.handleAddDeviceData(AddDeviceDataReq.newBuilder()
            .setDeviceId(34)
            .setDeptCode("12")
            .setRecordedAtIso8601("2026-07-03T12:05:06+08:00")
            .setDriverCode("JY_monitor_blt_q5")
            .setConnectionId("conn-1")
            .addData(AtomicDeviceDataPB.newBuilder()
                .setParamCode("heart_rate")
                .setRecordedStr("80")
                .build())
            .build());

        assertThat(response.getCode()).isEqualTo(DeviceDataGrpcService.CODE_OK);

        ArgumentCaptor<WriteRequest> captor = ArgumentCaptor.forClass(WriteRequest.class);
        verify(deviceDataWriteService).write(captor.capture());
        WriteRequest writeRequest = captor.getValue();
        assertThat(writeRequest.deptId()).isEqualTo(12);
        assertThat(writeRequest.deviceId()).isEqualTo(34);
        assertThat(writeRequest.recordedAtUtc()).isEqualTo(LocalDateTime.of(2026, 7, 3, 4, 5, 6));
        assertThat(writeRequest.values()).hasSize(1);
        assertThat(writeRequest.values().get(0).paramCode()).isEqualTo("heart_rate");
    }

    @Test
    void addDeviceDataRejectsNonNumericDeptCode() {
        DeviceDataGrpcService service = new DeviceDataGrpcService(deviceDataWriteService);

        AddDeviceDataResp response = service.handleAddDeviceData(AddDeviceDataReq.newBuilder()
            .setDeviceId(34)
            .setDeptCode("ICU")
            .setRecordedAtIso8601("2026-07-03T04:05:06Z")
            .build());

        assertThat(response.getCode()).isEqualTo(DeviceDataGrpcService.CODE_BAD_REQUEST);
        verifyNoInteractions(deviceDataWriteService);
    }

    @Test
    void addDeviceDataReturnsFailureWhenWriteFails() {
        Row row = new Row(12, 34, "heart_rate", LocalDateTime.of(2026, 7, 3, 4, 5), "80");
        when(deviceDataWriteService.write(any())).thenReturn(new WriteResult(
            0,
            1,
            List.of(new RowInsertResult(row, DeviceDataWriteService.STATUS_ERROR)),
            ""
        ));

        DeviceDataGrpcService service = new DeviceDataGrpcService(deviceDataWriteService);
        AddDeviceDataResp response = service.handleAddDeviceData(AddDeviceDataReq.newBuilder()
            .setDeviceId(34)
            .setDeptCode("12")
            .setRecordedAtIso8601("2026-07-03T04:05:06Z")
            .addData(AtomicDeviceDataPB.newBuilder()
                .setParamCode("heart_rate")
                .setRecordedStr("80")
                .build())
            .build());

        assertThat(response.getCode()).isEqualTo(DeviceDataGrpcService.CODE_WRITE_FAILED);
    }

    @Mock
    private DeviceDataWriteService deviceDataWriteService;
}
