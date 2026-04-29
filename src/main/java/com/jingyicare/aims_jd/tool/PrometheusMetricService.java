package com.jingyicare.aims_jd.tool;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class PrometheusMetricService {
    private final MeterRegistry meterRegistry;

    @Autowired
    public PrometheusMetricService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // 非数桥设备关闭
    public void incrementNonBridgeDeviceClosed(String ip) {
        meterRegistry.counter("jingyi_err_device_non_bridge_closed",
            "device_ip", ip
        ).increment();
    }

    // 无设备驱动设备关闭
    public void incrementNoDriverDeviceClosed(String driverCode, String ip) {
        meterRegistry.counter("jingyi_err_device_no_driver_closed",
            "driver_code", driverCode,
            "device_ip", ip
        ).increment();
    }

    // 无设备驱动实现设备关闭
    public void incrementNoDriverImplClosed(String driverCode, String ip) {
        meterRegistry.counter("jingyi_err_device_no_driver_impl_closed",
            "driver_code", driverCode,
            "device_ip", ip
        ).increment();
    }

    // 记录消息队列满了
    public void incrementMessageQueueFull(String driverCode, String ip) {
        meterRegistry.counter("jingyi_err_device_message_queue_full",
            "driver_code", driverCode,
            "device_ip", ip
        ).increment();
    }

    // 缓冲区满，根据上一个记录尾部截断
    public void incrementBufferExceeded1(String driverCode, String ip) {
        meterRegistry.counter("jingyi_err_device_buffer_exceeded1",
            "driver_code", driverCode,
            "device_ip", ip
        ).increment();
    }

    // 缓冲区满，强制截断
    public void incrementBufferExceeded2(String driverCode, String ip) {
        meterRegistry.counter("jingyi_err_device_buffer_exceeded2",
            "driver_code", driverCode,
            "device_ip", ip
        ).increment();
    }

    // 记录消息处理失败(队列满了)
    public void incrementMessageRejectionFailure(String driverCode, Integer deviceId) {
        meterRegistry.counter("jingyi_device_message_rejected",
            "driver_code", driverCode,
            "device_id", String.valueOf(deviceId)
        ).increment();
    }

    // 记录消息处理失败
    public void incrementMessageProcessingFailure(String driverCode, Integer deviceId) {
        meterRegistry.counter("jingyi_device_message_processing_failed",
            "driver_code", driverCode,
            "device_id", String.valueOf(deviceId)
        ).increment();
    }

    // 记录消息处理成功
    public void incrementMessageProcessingSuccess(String driverCode, Integer deviceId) {
        meterRegistry.counter("jingyi_device_message_processing_success",
            "driver_code", driverCode,
            "device_id", String.valueOf(deviceId)
        ).increment();
    }

    // 记录设备记录条数
    public void incrementDeviceDataRecords(String driverCode, Integer deviceId, int count) {
        meterRegistry.counter("jingyi_device_data_records",
            "driver_code", driverCode,
            "device_id", String.valueOf(deviceId)
        ).increment(count);
    }

    /**************************************
     * 记录 HL7 消息处理成功
     * @param hl7MsgType HL7 消息类型
     */
    public void incrementHl7MessageSuccess(String ip, String hl7MsgType) {
        meterRegistry.counter("jingyi_hl7_message_success_total", "device_ip", ip).increment();
        meterRegistry.counter("jingyi_hl7_message_success_type_total", "device_ip", ip, "hl7_msg_type", hl7MsgType).increment();
    }

    /**
     * 增加设备连接成功计数
     * @param deviceIp 设备 IP
     */
    public void incrementDeviceConnectionSuccess(String deviceIp) {
        meterRegistry.counter("aims_jd_connection_success_total", "device_ip", deviceIp)
                .increment();
    }

    /**
     * 增加设备连接失败计数
     * @param reason 失败原因
     * @param deviceIp 设备 IP（可选）
     */
    public void incrementDeviceConnectionFailure(String reason, String deviceIp) {
        meterRegistry.counter("aims_jd_connection_failure_total",
                "reason", reason,
                "device_ip", deviceIp != null ? deviceIp : "unknown")
                .increment();
    }

    /**
     * 增加设备数据处理错误计数
     * @param deviceIp 设备 IP
     */
    public void incrementDeviceDataError(String deviceIp) {
        meterRegistry.counter("aims_jd_data_error_total", "device_ip", deviceIp)
                .increment();
    }

    /**
     * 增加设备同步失败计数
     * @param reason 失败原因
     * @param deviceIp 设备 IP（可选）
     */
    public void incrementDeviceSyncFailure(String reason, String deviceIp) {
        meterRegistry.counter("aims_jd_sync_failure_total",
                "reason", reason,
                "device_ip", deviceIp != null ? deviceIp : "unknown")
                .increment();
    }

    /**
     * 增加设备移除计数
     * @param deviceIp 设备 IP
     */
    public void incrementDeviceRemoved(String deviceIp) {
        meterRegistry.counter("aims_jd_removed_total", "device_ip", deviceIp)
                .increment();
    }

    /**
     * 设置当前设备总数
     * @param supplier 提供当前设备数的函数
     */
    public void gaugeDeviceCount(Supplier<Number> supplier) {
        Gauge.builder("aims_jd_count", supplier)
                .description("Current number of connected devices")
                .register(meterRegistry);
    }
}

