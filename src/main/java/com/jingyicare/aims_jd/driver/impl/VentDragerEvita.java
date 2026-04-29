package com.jingyicare.aims_jd.driver.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.frame.DragerEvitaFrameDecoder;
import com.jingyicare.aims_jd.driver.frame.FrameDecoder;
import com.jingyicare.aims_jd.driver.impl.base.AbstractSingleDeviceProtocolDriver;
import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;
import com.jingyicare.aims_jd.driver.session.ProtocolSession;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * Drager Evita 单设备驱动。
 * 第二阶段迁移后由 DriverPlugin + ProtocolSession 承接原先的握手/解析流程。
 */
@Slf4j
public class VentDragerEvita extends AbstractSingleDeviceProtocolDriver {
    public static final String DRIVER_CODE = "jd_vent_drager_evita";
    private static final long REQ_SETTINGS_INTERVAL_NS = 40L * 1_000_000_000L;
    private static final long REQ_MEASURES_INTERVAL_NS = 10L * 1_000_000_000L;
    private static final long CMD_DELAY_MS = 20L;

    public VentDragerEvita(DriverContext context) {
        super(context);
        this.page1ParamMap = obsPageItemMap("page1");
        this.page2ParamMap = obsPageItemMap("page2");
        this.settingParamMap = obsPageItemMap("setting");
        if (page1ParamMap.isEmpty()) {
            log.error("Device ip={} has no obs page 'page1' configured!", context.remoteIp());
        }
        if (page2ParamMap.isEmpty()) {
            log.error("Device ip={} has no obs page 'page2' configured!", context.remoteIp());
        }
        if (settingParamMap.isEmpty()) {
            log.error("Device ip={} has no obs page 'setting' configured!", context.remoteIp());
        }
    }

    @Override
    public String driverCode() {
        return DRIVER_CODE;
    }

    @Override
    public FrameDecoder frameDecoder() {
        return frameDecoder;
    }

    @Override
    public ProtocolSession protocolSession() {
        return protocolSession;
    }

    private final class DragerProtocolSession implements ProtocolSession {
        @Override
        public void onFrame(Frame frame, ProtocolSessionContext sessionContext) {
            byte[] bytes = normalizeFrame(frame.copyPayload());
            if (bytes.length < 5) {
                return;
            }

            log.info("Received message from device ip={}\npayload={}",
                context.remoteIp(), TxtDumper.bytesToString(bytes));

            boolean isCmd = bytes[0] == Consts.ESC;
            boolean isAck = bytes[0] == Consts.SOH;
            byte cmd = bytes[1];

            if (isCmd && cmd == 0x51) {
                sessionContext.send(initConnCmdAck, CMD_DELAY_MS);
                return;
            }
            if (isCmd && cmd == 0x52) {
                sessionContext.send(requestDevIdCmdAck, CMD_DELAY_MS);
                return;
            }
            if (isCmd && cmd == 0x30) {
                handleNopFrame(sessionContext);
                return;
            }
            if (isAck && cmd == 0x29) {
                parseSettings(bytes, sessionContext);
                return;
            }
            if (isAck && cmd == 0x24) {
                parseMeasures(bytes, page1ParamMap, "page1", sessionContext);
                return;
            }
            if (isAck && cmd == 0x2B) {
                parseMeasures(bytes, page2ParamMap, "page2", sessionContext);
                return;
            }

            log.error("Unexpected message from device ip={}, payload={}",
                context.remoteIp(), TxtDumper.bytesToString(bytes));
        }
    }

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new VentDragerEvita(context);
        }
    }

    private void handleNopFrame(ProtocolSessionContext sessionContext) {
        sessionContext.send(nopCmdAck, CMD_DELAY_MS);

        long nowNs = System.nanoTime();
        if (nowNs - lastGetSettingsAtNs >= REQ_SETTINGS_INTERVAL_NS) {
            sessionContext.send(getSettingsCmd, CMD_DELAY_MS);
            lastGetSettingsAtNs = nowNs;
            return;
        }

        if (nowNs - lastGetMeasuresAtNs >= REQ_MEASURES_INTERVAL_NS) {
            if (isMeasuresPage1) {
                sessionContext.send(getMeasuresCmd, CMD_DELAY_MS);
                isMeasuresPage1 = false;
            } else {
                sessionContext.send(getMeasuresPage2Cmd, CMD_DELAY_MS);
                isMeasuresPage1 = true;
            }
            lastGetMeasuresAtNs = nowNs;
        }
    }

    private void parseSettings(byte[] payload, ProtocolSessionContext sessionContext) {
        List<ObservationValue> values = new ArrayList<>();
        String recordedAtIso8601 = nowIso8601Utc();
        int idx = 2;
        while (idx + 6 < payload.length - 3) {
            String obCode = new String(payload, idx, 2, context.charset());
            String value = new String(payload, idx + 2, 5, context.charset()).trim();
            String paramCode = settingParamMap.get(obCode);
            if (paramCode != null) {
                values.add(new ObservationValue(paramCode, value, recordedAtIso8601));
                log.info("Device ip={} setting paramCode={} value={}", context.remoteIp(), paramCode, value);
            }
            dumpDebug(sessionContext, "<SETTING> obCode=" + obCode + " paramCode=" + paramCode + " value=" + value);
            idx += 7;
        }

        publishObservationBatch(sessionContext, values, TxtDumper.bytesToString(payload));
        dumpObservation(sessionContext, "Drager setting", TxtDumper.bytesToString(payload), values);
    }

    private void parseMeasures(
        byte[] payload,
        Map<String, String> measurementMap,
        String pageName,
        ProtocolSessionContext sessionContext
    ) {
        List<ObservationValue> values = new ArrayList<>();
        String recordedAtIso8601 = nowIso8601Utc();
        int idx = 2;
        while (idx + 5 < payload.length - 3) {
            String obCode = new String(payload, idx, 2, context.charset());
            String value = new String(payload, idx + 2, 4, context.charset()).trim();
            value = normalizeMeasureValue(obCode, value);

            String paramCode = measurementMap.get(obCode);
            if (paramCode != null) {
                values.add(new ObservationValue(paramCode, value, recordedAtIso8601));
                log.info("Device ip={} measure paramCode={} value={}", context.remoteIp(), paramCode, value);
            }
            dumpDebug(sessionContext, "<DATA> obCode=" + obCode + " paramCode=" + paramCode + " value=" + value);
            idx += 6;
        }

        publishObservationBatch(sessionContext, values, TxtDumper.bytesToString(payload));
        dumpObservation(sessionContext, "Drager measure " + pageName, TxtDumper.bytesToString(payload), values);
    }

    private void dumpObservation(
        ProtocolSessionContext sessionContext,
        String title,
        String rawPayload,
        List<ObservationValue> values
    ) {
        StringBuilder builder = new StringBuilder()
            .append("=== ").append(title).append(" ").append(driverCode()).append(" ===\n")
            .append(rawPayload)
            .append('\n');
        if (!values.isEmpty()) {
            builder.append("\n=== mapped values ===\n");
            for (ObservationValue value : values) {
                builder.append(value.paramCode())
                    .append('=')
                    .append(value.recordedStr())
                    .append(" @ ")
                    .append(value.recordedAtIso8601())
                    .append('\n');
            }
        }
        dumpText(sessionContext, builder.toString());
    }

    private String normalizeMeasureValue(String obCode, String value) {
        if (obCode.equals("73") || obCode.equals("78") || obCode.equals("0B")) {
            return mbarToCmH2O(value);
        }
        if (obCode.equals("76")) {
            try {
                double v = Double.parseDouble(value);
                v = v * 60.0 / 1000.0;
                return String.format("%.2f", v);
            } catch (NumberFormatException nfe) {
                log.warn("Device ip={} vent_peak_flow_rate parse value={} error: {}",
                    context.remoteIp(), value, nfe.getMessage());
            }
        }
        return value;
    }

    private String mbarToCmH2O(String mbar) {
        try {
            double v = Double.parseDouble(mbar);
            v = v * 100.0 / 98.0665;
            return String.format("%.2f", v);
        } catch (NumberFormatException nfe) {
            log.warn("Device ip={} pressure parse value={} error: {}",
                context.remoteIp(), mbar, nfe.getMessage());
        }
        return mbar;
    }

    private static byte[] normalizeFrame(byte[] bytes) {
        int startIdx = 0;
        while (startIdx < bytes.length - 1 && bytes[startIdx] == Consts.ESC && bytes[startIdx + 1] == Consts.ESC) {
            startIdx++;
        }
        return startIdx > 0 ? Arrays.copyOfRange(bytes, startIdx, bytes.length) : bytes;
    }

    private long lastGetSettingsAtNs = 0L;
    private long lastGetMeasuresAtNs = 0L;
    private boolean isMeasuresPage1 = true;

    private final Map<String, String> page1ParamMap;
    private final Map<String, String> page2ParamMap;
    private final Map<String, String> settingParamMap;

    private final FrameDecoder frameDecoder = new DragerEvitaFrameDecoder();
    private final ProtocolSession protocolSession = new DragerProtocolSession();

    private final byte[] initConnCmdAck = new byte[] {
        (byte) 0x01, (byte) 0x51, (byte) 0x35, (byte) 0x32, (byte) 0x0D
    };
    private final byte[] requestDevIdCmdAck = new byte[] {
        (byte) 0x01, (byte) 0x52, (byte) 0x38, (byte) 0x38, (byte) 0x38,
        (byte) 0x38, (byte) 0x27, (byte) 0x44, (byte) 0x72, (byte) 0x61,
        (byte) 0x65, (byte) 0x67, (byte) 0x65, (byte) 0x72, (byte) 0x20,
        (byte) 0x44, (byte) 0x65, (byte) 0x76, (byte) 0x69, (byte) 0x63,
        (byte) 0x65, (byte) 0x27, (byte) 0x30, (byte) 0x33, (byte) 0x2E,
        (byte) 0x30, (byte) 0x30, (byte) 0x3A, (byte) 0x30, (byte) 0x33,
        (byte) 0x2E, (byte) 0x30, (byte) 0x30, (byte) 0x43, (byte) 0x37,
        (byte) 0x0D
    };
    private final byte[] nopCmdAck = new byte[] {
        (byte) 0x01, (byte) 0x30, (byte) 0x33, (byte) 0x31, (byte) 0x0D
    };
    private final byte[] getSettingsCmd = new byte[] {
        (byte) 0x1B, (byte) 0x29, (byte) 0x34, (byte) 0x34, (byte) 0x0D
    };
    private final byte[] getMeasuresCmd = new byte[] {
        (byte) 0x1B, (byte) 0x24, (byte) 0x33, (byte) 0x46, (byte) 0x0D
    };
    private final byte[] getMeasuresPage2Cmd = new byte[] {
        (byte) 0x1B, (byte) 0x2B, (byte) 0x34, (byte) 0x36, (byte) 0x0D
    };
}

