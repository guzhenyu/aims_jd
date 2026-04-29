package com.jingyicare.aims_jd.driver;

import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.impl.*;
import com.jingyicare.aims_jd.driver.registry.DefaultDriverRegistry;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;
import com.jingyicare.aims_jd.driver.registry.DriverRegistry;
import com.jingyicare.aims_jd.tool.*;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;
import com.jingyicare.aims_jd.utils.Consts;
import com.jingyicare.aims_jd.utils.DeviceSourceModes;
import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.*;

@Slf4j
public final class DeviceConnFactory {
    @FunctionalInterface
    public interface LegacyHandlerFactory {
        DeviceConnHandler create(
            SocketChannel socketChannel,
            String ip,
            DeviceInfoPB deviceInfo,
            DevObservationConfigPB obsConfig,
            Map<String, String> paramMap,
            ObservationPublisher publisher,
            PrometheusMetricService metricService,
            TxtDumper txtDumper
        );
    }

    private static final DriverRegistry DRIVER_REGISTRY = new DefaultDriverRegistry();
    private static final Map<String, LegacyHandlerFactory> LEGACY_FACTORIES = new ConcurrentHashMap<>();

    static {
        registerDriverPluginFactory(new MindrayN12Monitor.Factory());
        registerDriverPluginFactory(new BltQ5Monitor.Factory());
        registerDriverPluginFactory(new MindrayWatoEx55Anesthesia.Factory());
    }

    private DeviceConnFactory() {}

    public static DriverRegistry driverRegistry() {
        return DRIVER_REGISTRY;
    }

    public static void registerDriverPluginFactory(DriverPluginFactory factory) {
        DRIVER_REGISTRY.register(factory);
    }

    public static void registerLegacyFactory(String driverCode, LegacyHandlerFactory factory) {
        if (driverCode == null || driverCode.isBlank()) {
            throw new IllegalArgumentException("driverCode must not be blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("LegacyHandlerFactory must not be null");
        }
        LEGACY_FACTORIES.put(driverCode, factory);
    }

    public static DeviceConnHandler newHandler(
        String deviceDriverCode,
        SocketChannel socketChannel,
        String ip,
        DeviceInfoPB deviceInfo,
        DevObservationConfigPB obsConfig,
        Map<String, String> paramMap,
        ObservationPublisher publisher,
        PrometheusMetricService metricService,
        TxtDumper txtDumper
    ) {
        if (deviceDriverCode == null || deviceDriverCode.isEmpty()) {
            return null;
        }
        if (deviceInfo != null && !DeviceSourceModes.isDirectSource(deviceInfo)) {
            log.warn("Skip handler creation for non-source device: driverCode={} ip={} deviceId={} sourceMode={} upstreamDeviceId={}",
                deviceDriverCode, ip, deviceInfo.getId(), deviceInfo.getSourceMode(), deviceInfo.getUpstreamDeviceId());
            return null;
        }
        if (DeviceSourceModes.isOutboundClient(deviceInfo)) {
            log.warn("Skip inbound handler creation for outbound source device: driverCode={} ip={} deviceId={}",
                deviceDriverCode, ip, deviceInfo.getId());
            return null;
        }

        Optional<DriverPluginFactory> pluginFactory = DRIVER_REGISTRY.find(deviceDriverCode);
        if (pluginFactory.isPresent()) {
            return buildPluginHandler(
                pluginFactory.get(),
                socketChannel,
                ip,
                deviceInfo,
                obsConfig,
                paramMap,
                publisher,
                metricService,
                txtDumper
            );
        }

        LegacyHandlerFactory factory = LEGACY_FACTORIES.get(deviceDriverCode);
        if (factory == null) {
            return null;
        }
        return factory.create(
            socketChannel, ip, deviceInfo, obsConfig, paramMap,
            publisher, metricService, txtDumper
        );
    }

    private static DeviceConnHandler buildPluginHandler(
        DriverPluginFactory pluginFactory,
        SocketChannel socketChannel,
        String ip,
        DeviceInfoPB deviceInfo,
        DevObservationConfigPB obsConfig,
        Map<String, String> paramMap,
        ObservationPublisher publisher,
        PrometheusMetricService metricService,
        TxtDumper txtDumper
    ) {
        try {
            DriverContext context = new DriverContext(
                ip,
                deviceInfo,
                obsConfig,
                paramMap,
                obsConfig.getObsPageList(),
                Consts.CHARSET,
                Consts.ZONE_ID
            );
            DriverPlugin plugin = pluginFactory.create(context);
            return new PluginBackedDeviceConnHandler(
                socketChannel,
                ip,
                context,
                plugin,
                publisher,
                metricService,
                txtDumper
            );
        } catch (Exception e) {
            log.error("Failed to create plugin-backed handler for driverCode={} ip={}",
                pluginFactory.driverCode(), ip, e);
            return null;
        }
    }
}

