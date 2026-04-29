package com.jingyicare.aims_jd.driver.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.frame.Abl90FrameDecoder;
import com.jingyicare.aims_jd.driver.frame.FrameDecoder;
import com.jingyicare.aims_jd.driver.impl.base.AbstractSingleDeviceProtocolDriver;
import com.jingyicare.aims_jd.driver.model.BgaBatch;
import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;
import com.jingyicare.aims_jd.driver.session.ProtocolSession;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.utils.Consts;
import com.jingyicare.aims_jd.utils.TimeUtils;

/**
 * Radiometer ABL90 单设备血气驱动。
 * 第二阶段迁移后改为 DriverPlugin + BgaBatch 发布。
 */
@Slf4j
public class BgaRadiometerAbl90 extends AbstractSingleDeviceProtocolDriver {
    public static final String DRIVER_CODE = "jd_bga_radiometer_abl90";
    private static final long CMD_DELAY_MS = 0L;

    public BgaRadiometerAbl90(DriverContext context) {
        super(context);
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

    private final class Abl90ProtocolSession implements ProtocolSession {
        @Override
        public void onFrame(Frame frame, ProtocolSessionContext sessionContext) {
            byte[] bytes = frame.copyPayload();
            if (bytes.length == 0) {
                return;
            }

            log.info("\n\nReceived Abl90 message: {}\nascii={}\n",
                TxtDumper.bytesToString(bytes),
                new String(bytes, context.charset()));

            if (bytes[0] == Consts.ENQ) {
                log.info("ENQ message from Abl90");
                sessionContext.send(ackCmd, CMD_DELAY_MS);
                return;
            }

            if (bytes[0] == Consts.EOT) {
                log.info("EOT message from Abl90");
                finishBatch(sessionContext, "EOT message", true);
                return;
            }

            if (bytes.length > 6 && bytes[0] == Consts.STX && bytes[bytes.length - 2] == Consts.CR && bytes[bytes.length - 1] == Consts.LF) {
                String msg = new String(bytes, 1, bytes.length - 7, context.charset());
                parseTextMessage(msg);

                if (bytes[bytes.length - 5] == Consts.ETB) {
                    log.info("STX-ETB message from Abl90: {}", msg);
                    sessionContext.send(ackCmd, CMD_DELAY_MS);
                    return;
                }

                if (bytes[bytes.length - 5] == Consts.ETX) {
                    log.info("STX-ETX message from Abl90: {}", msg);
                    finishBatch(sessionContext, "ETX message", false);
                    sessionContext.send(ackCmd, CMD_DELAY_MS);
                    return;
                }
            }

            sessionContext.send(ackCmd, CMD_DELAY_MS);
            log.error("Unexpected message from Abl90: {}", TxtDumper.bytesToString(bytes));
        }
    }

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new BgaRadiometerAbl90(context);
        }
    }

    private void parseTextMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return;
        }

        bgaPatientInfo.appendRaw(msg);
        List<String> parts = Arrays.asList(msg.split("\\|"));
        if (parts.isEmpty()) {
            return;
        }

        String segmentId = parts.get(0);
        if (segmentId.endsWith("P") && parts.size() >= 4) {
            bgaPatientInfo.bedNo = parts.get(3);
            return;
        }

        if (segmentId.endsWith("H") && parts.size() >= 14) {
            String timeStr = parts.get(13);
            LocalDateTime dt = TimeUtils.parse(timeStr, "yyyyMMddHHmmss");
            if (dt != null) {
                bgaPatientInfo.effectiveTimeUtc = TimeUtils.getUtcFromLocalDateTime(dt, Consts.ZONE_ID);
            }
            return;
        }

        if (segmentId.endsWith("R") && parts.size() >= 5) {
            String obCode = resolveObCode(parts.get(2));
            String obValue = parts.get(3);
            String paramCode = context.paramMap().get(obCode);
            log.info("Parsed Abl90 observation: obCode={}, obValue={}, mapped paramCode={}",
                obCode, obValue, paramCode);
            if (paramCode != null) {
                bgaPatientInfo.paramMap.put(paramCode, obValue);
                if (parts.size() >= 12) {
                    String timeStr = parts.get(11);
                    LocalDateTime dt = TimeUtils.parse(timeStr, "yyyyMMddHHmmss");
                    if (dt != null) {
                        bgaPatientInfo.effectiveTimeUtc = TimeUtils.getUtcFromLocalDateTime(dt, Consts.ZONE_ID);
                    }
                }
            }
        }
    }

    private void finishBatch(ProtocolSessionContext sessionContext, String notes, boolean forceReset) {
        boolean hasIdentity = bgaPatientInfo.bedNo != null && bgaPatientInfo.effectiveTimeUtc != null;
        if (!hasIdentity) {
            log.warn("{} - Incomplete BGA patient info, cannot publish BgaBatch: bedNo={}, effectiveTimeUtc={}",
                notes, bgaPatientInfo.bedNo, bgaPatientInfo.effectiveTimeUtc);
            if (forceReset) {
                bgaPatientInfo.reset();
            }
            return;
        }

        BgaBatch batch = newBgaBatch(
            bgaPatientInfo.bedNo,
            1,
            TimeUtils.toIso8601String(bgaPatientInfo.effectiveTimeUtc, "UTC"),
            Map.copyOf(bgaPatientInfo.paramMap),
            bgaPatientInfo.rawMessage()
        );
        publishBgaBatch(sessionContext, batch);

        dumpText(sessionContext, "=== ABL90 BGA " + driverCode() + " ===\n"
            + batch.rawMessage()
            + "\n=== mapped details ===\n"
            + batch.details()
            + '\n');

        if (hasIdentity || forceReset) {
            bgaPatientInfo.reset();
        }
    }

    private static String resolveObCode(String codeMsg) {
        if (codeMsg == null || codeMsg.isBlank()) {
            return "";
        }
        List<String> codeParts = Arrays.asList(codeMsg.split("\\^"));
        return codeParts.size() >= 4 ? codeParts.get(3) : "";
    }

    static final class BgaPatientInfo {
        private String bedNo;
        private LocalDateTime effectiveTimeUtc = TimeUtils.getNowUtc();
        private final Map<String, String> paramMap = new HashMap<>();
        private final StringBuilder rawMessage = new StringBuilder();

        private void appendRaw(String rawSegment) {
            if (rawSegment == null || rawSegment.isBlank()) {
                return;
            }
            rawMessage.append(rawSegment).append('\n');
        }

        private String rawMessage() {
            return rawMessage.toString();
        }

        private void reset() {
            bedNo = null;
            effectiveTimeUtc = TimeUtils.getNowUtc();
            paramMap.clear();
            rawMessage.setLength(0);
        }
    }

    private final FrameDecoder frameDecoder = new Abl90FrameDecoder();
    private final ProtocolSession protocolSession = new Abl90ProtocolSession();
    private final BgaPatientInfo bgaPatientInfo = new BgaPatientInfo();

    private final byte[] ackCmd = new byte[] {
        (byte) 0x06
    };
}

