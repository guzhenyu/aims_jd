package com.jingyicare.aims_jd.driver.impl.base;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.CentralStationDriverPlugin;
import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.SourceMode;
import com.jingyicare.aims_jd.driver.frame.FrameDecoder;
import com.jingyicare.aims_jd.driver.frame.MllpFrameDecoder;
import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.ObservationBatch;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.driver.session.ProtocolSession;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.tool.publisher.ObservationPublisher;
import com.jingyicare.aims_jd.utils.Consts;
import com.jingyicare.aims_jd.utils.Hl7Utils;
import com.jingyicare.aims_jd.utils.TimeUtils;

/**
 * HL7/MLLP 中央站驱动公共基类。
 * 第一阶段把 MLLP 分帧、ER7 归一化、床号提取、OBX 映射、ACK 和 dump 收拢到这里。
 */
@Slf4j
public abstract class AbstractHl7MllpCentralStationDriver implements CentralStationDriverPlugin {
    protected AbstractHl7MllpCentralStationDriver(DriverContext context) {
        this.context = context;
        this.frameDecoder = new MllpFrameDecoder();
        this.protocolSession = new Hl7MllpProtocolSession();
    }

    @Override
    public FrameDecoder frameDecoder() {
        return frameDecoder;
    }

    @Override
    public ProtocolSession protocolSession() {
        return protocolSession;
    }

    @Override
    public SourceMode sourceMode() {
        return SourceMode.INBOUND_SERVER;
    }

    protected Charset charset() {
        return context.charset() == null ? StandardCharsets.UTF_8 : context.charset();
    }

    protected boolean shouldProcessMessage(String messageType, String er7) {
        return messageType != null && messageType.startsWith("ORU");
    }

    protected Optional<byte[]> buildAck(String messageControlId) {
        return Optional.empty();
    }

    protected long ackDelayMs() {
        return 5L;
    }

    protected Optional<String> normalizeMessageType(String er7) {
        return Optional.ofNullable(extractMshField(er7, 9));
    }

    protected Optional<String> normalizeMessageControlId(String er7) {
        return Optional.ofNullable(extractMshField(er7, 10));
    }

    protected Optional<ObservationBatch> mapEr7ToObservationBatch(
        String er7,
        ProtocolSessionContext sessionContext,
        String messageControlId
    ) {
        Optional<Message> parsedMessage = parseMessage(er7, messageControlId);
        Optional<Terser> terser = parsedMessage.map(Terser::new);
        String bedNumber = resolveBedNumber(terser, er7).orElse("");
        String recordedAtIso8601 = resolveObservationTimestampIso8601();
        List<ObservationValue> values = mapObservationValues(terser, er7, recordedAtIso8601, messageControlId);

        ObservationBatch batch = newBatch(bedNumber, values, er7);
        dumpRawHl7(sessionContext, er7, batch);
        return Optional.of(batch);
    }

    protected Optional<String> resolveBedNumber(Optional<Terser> terser, String er7) {
        String path = context.observationConfig() == null ? "" : context.observationConfig().getHl7BedNumberPath();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String bedNumber = terser.map(t -> Hl7Utils.safeGet(t, path)).orElse("");
        if (bedNumber == null || bedNumber.isBlank()) {
            bedNumber = getSegmentValueByPath(er7, path);
        }
        return Optional.ofNullable(bedNumber).filter(s -> !s.isBlank());
    }

    protected String resolveObservationTimestampIso8601() {
        return TimeUtils.toIso8601String(
            TimeUtils.getNowUtc().withSecond(0).withNano(0),
            "UTC"
        );
    }

    protected List<ObservationValue> mapObservationValues(
        Optional<Terser> terser,
        String er7,
        String recordedAtIso8601,
        String messageControlId
    ) {
        List<ObservationValue> values = List.of();
        boolean usedRawObx = false;
        int obxCount = 0;
        Set<Integer> loggedObxIndexes = new HashSet<>();

        if (terser.isPresent()) {
            try {
                Terser t = terser.get();
                obxCount = t.getFinder().getRoot().getAll("OBX").length;
                if (obxCount > 0) {
                    values = mapObservationValuesByHapi(
                        t,
                        recordedAtIso8601,
                        messageControlId,
                        loggedObxIndexes
                    );
                }
            } catch (Exception e) {
                usedRawObx = true;
                log.warn("OBX group missing in HL7 message, falling back to raw parse: msgId={} err={}",
                    messageControlId, e.toString());
            }
        }

        if (values.isEmpty()) {
            List<ObservationValue> rawValues = mapObservationValuesByRaw(
                er7,
                recordedAtIso8601,
                messageControlId,
                loggedObxIndexes
            );
            if (!rawValues.isEmpty()) {
                values = rawValues;
            }
            usedRawObx = true;
            obxCount = countObxSegments(er7);
        }

        if (values.isEmpty()) {
            log.warn("No mapped OBX in HL7 message: msgId={} obxCount={} rawParsed={} driver={} ip={}",
                messageControlId, obxCount, usedRawObx, driverCode(), context.remoteIp());
        }
        return values;
    }

    protected List<ObservationValue> mapObservationValuesByHapi(Terser terser, String recordedAtIso8601) {
        return mapObservationValuesByHapi(terser, recordedAtIso8601, "", new HashSet<>());
    }

    protected List<ObservationValue> mapObservationValuesByHapi(
        Terser terser,
        String recordedAtIso8601,
        String messageControlId,
        Set<Integer> loggedObxIndexes
    ) {
        List<ObservationValue> values = new ArrayList<>();
        String prefix = context.observationConfig().getHl7ObPathPrefix();
        String codePath = context.observationConfig().getHl7ObCodePath();
        String valuePath = context.observationConfig().getHl7ObValuePath();

        int obxCount;
        try {
            obxCount = terser.getFinder().getRoot().getAll("OBX").length;
        } catch (Exception e) {
            return values;
        }
        for (int i = 0; i < obxCount; i++) {
            String obCode = Hl7Utils.safeGet(terser, prefix + "(" + i + ")-" + codePath);
            if (obCode == null || obCode.isBlank()) {
                continue;
            }

            String normalizedObCode = obCode.trim();
            Optional<String> paramCode = mapObservationCode(normalizedObCode);
            String paramValue = Hl7Utils.safeGet(terser, prefix + "(" + i + ")-" + valuePath);
            String valueType = Hl7Utils.safeGet(terser, prefix + "(" + i + ")-2");
            String abnormalFlag = Hl7Utils.safeGet(terser, prefix + "(" + i + ")-8");
            String resultStatus = Hl7Utils.safeGet(terser, prefix + "(" + i + ")-11");
            logCentralStationOutputObCode(
                messageControlId,
                i,
                normalizedObCode,
                paramCode.orElse(""),
                paramValue,
                loggedObxIndexes
            );
            if (paramCode.isEmpty()) {
                continue;
            }
            if (!isValidNumericObservation(valueType, paramValue, abnormalFlag, resultStatus)) {
                log.debug("Skip invalid OBX: msgId={} obxIndex={} obCode={} valueType={} value={} abnormal={} status={}",
                    messageControlId, i, normalizedObCode, valueType, paramValue, abnormalFlag, resultStatus);
                continue;
            }

            values.add(new ObservationValue(paramCode.get(), paramValue, recordedAtIso8601));
        }
        return values;
    }

    protected List<ObservationValue> mapObservationValuesByRaw(String er7, String recordedAtIso8601) {
        return mapObservationValuesByRaw(er7, recordedAtIso8601, "", new HashSet<>());
    }

    protected List<ObservationValue> mapObservationValuesByRaw(
        String er7,
        String recordedAtIso8601,
        String messageControlId,
        Set<Integer> loggedObxIndexes
    ) {
        List<ObservationValue> values = new ArrayList<>();
        int[] codePath = parseFieldComponent(context.observationConfig().getHl7ObCodePath());
        int[] valuePath = parseFieldComponent(context.observationConfig().getHl7ObValuePath());
        if (codePath[0] <= 0 || valuePath[0] <= 0) {
            return values;
        }

        int obxIndex = 0;
        for (String segment : er7.split("\r")) {
            if (!segment.startsWith("OBX|")) {
                continue;
            }

            int currentObxIndex = obxIndex++;
            String obCode = getSegmentValue(segment, codePath[0], codePath[1]).trim();
            if (obCode.isBlank()) {
                continue;
            }

            Optional<String> paramCode = mapObservationCode(obCode);
            String paramValue = getSegmentValue(segment, valuePath[0], valuePath[1]);
            String valueType = getSegmentValue(segment, 2, 0);
            String abnormalFlag = getSegmentValue(segment, 8, 0);
            String resultStatus = getSegmentValue(segment, 11, 0);
            logCentralStationOutputObCode(
                messageControlId,
                currentObxIndex,
                obCode,
                paramCode.orElse(""),
                paramValue,
                loggedObxIndexes
            );
            if (paramCode.isEmpty()) {
                continue;
            }
            if (!isValidNumericObservation(valueType, paramValue, abnormalFlag, resultStatus)) {
                log.debug("Skip invalid raw OBX: msgId={} obxIndex={} obCode={} valueType={} value={} abnormal={} status={}",
                    messageControlId, currentObxIndex, obCode, valueType, paramValue, abnormalFlag, resultStatus);
                continue;
            }

            values.add(new ObservationValue(paramCode.get(), paramValue, recordedAtIso8601));
        }
        return values;
    }

    protected void dumpRawHl7(ProtocolSessionContext sessionContext, String er7, ObservationBatch batch) {
        if (sessionContext == null) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("=== HL7 Message ").append(driverCode()).append(" ===\n")
            .append(er7.replace("\r", "\n"))
            .append('\n');
        if (batch != null && !batch.values().isEmpty()) {
            builder.append("\n=== mapped observation values ===\n");
            for (ObservationValue value : batch.values()) {
                builder.append(value.paramCode())
                    .append('=')
                    .append(value.recordedStr())
                    .append(" @ ")
                    .append(value.recordedAtIso8601())
                    .append('\n');
            }
        }
        sessionContext.txtDumper().dump(builder.toString());
    }

    protected Optional<Message> parseMessage(String er7, String messageControlId) {
        try {
            return Optional.of(new PipeParser().parse(er7));
        } catch (Exception e) {
            log.warn("HL7 parse error: {} msgId={} rawAscii={}",
                e.toString(), messageControlId, er7.replace("\r", "\n"));
            return Optional.empty();
        }
    }

    protected Optional<String> mapObservationCode(String obCode) {
        if (obCode == null || obCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(context.paramMap().get(obCode));
    }

    protected String observationCodeLogContext() {
        return "";
    }

    private void logCentralStationOutputObCode(
        String messageControlId,
        int obxIndex,
        String obCode,
        String mappedParamCode,
        String paramValue,
        Set<Integer> loggedObxIndexes
    ) {
        if (obCode == null || obCode.isBlank()) {
            return;
        }
        if (loggedObxIndexes != null && !loggedObxIndexes.add(obxIndex)) {
            return;
        }
        String extraContext = observationCodeLogContext();
        if (extraContext == null || extraContext.isBlank()) {
            log.info("Central station output item.ob_code: msgId={} obxIndex={} driver={} ip={} item.ob_code={} mappedParamCode={} value={}",
                messageControlId, obxIndex, driverCode(), context.remoteIp(), obCode, mappedParamCode, paramValue);
            return;
        }
        log.info("Central station output item.ob_code: msgId={} obxIndex={} driver={} ip={} item.ob_code={} mappedParamCode={} value={} {}",
            messageControlId, obxIndex, driverCode(), context.remoteIp(), obCode, mappedParamCode, paramValue, extraContext);
    }

    protected ObservationBatch newBatch(String bedNumber, List<ObservationValue> values, String rawMessage) {
        return new ObservationBatch(
            context.deviceId(),
            context.deviceType(),
            bedNumber,
            values,
            defaultObservationAttributes(),
            rawMessage
        );
    }

    protected static boolean hasObxSegment(String er7) {
        if (er7 == null || er7.isEmpty()) {
            return false;
        }
        return er7.startsWith("OBX|") || er7.contains("\rOBX|");
    }

    protected static int countObxSegments(String er7) {
        int count = 0;
        for (String segment : er7.split("\r")) {
            if (segment.startsWith("OBX|")) {
                count++;
            }
        }
        return count;
    }

    protected static String extractMshField(String er7, int fieldIndex) {
        if (er7 == null || er7.isBlank() || fieldIndex < 2) {
            return "";
        }
        int end = er7.indexOf('\r');
        String msh = end >= 0 ? er7.substring(0, end) : er7;
        if (!msh.startsWith("MSH")) {
            return "";
        }
        String[] fields = msh.split("\\|", -1);
        int idx = fieldIndex - 1;
        if (idx < 0 || idx >= fields.length) {
            return "";
        }
        return fields[idx];
    }

    protected static String normalizeEr7(String er7) {
        if (er7 == null) {
            return "";
        }

        String normalized = er7.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace("\\r\\n", "\r")
            .replace("\\n", "\r")
            .replace("\\r", "\r")
            .replace("\r\n", "\r")
            .replace("\n", "\r");
    }

    protected static byte[] buildMllpFrame(String payload) {
        byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[body.length + 3];
        frame[0] = Consts.VT;
        System.arraycopy(body, 0, frame, 1, body.length);
        frame[body.length + 1] = Consts.FS;
        frame[body.length + 2] = Consts.CR;
        return frame;
    }

    protected static String getSegmentValueByPath(String er7, String path) {
        if (er7 == null || path == null || path.isEmpty()) {
            return "";
        }
        String[] parts = path.split("-");
        if (parts.length < 2) {
            return "";
        }

        String segment = parts[0].trim();
        int fieldIndex = parseIntSafe(parts[1]);
        int componentIndex = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
        if (segment.isEmpty() || fieldIndex <= 0) {
            return "";
        }

        for (String current : er7.split("\r")) {
            if (current.startsWith(segment + "|")) {
                return getSegmentValue(current, fieldIndex, componentIndex);
            }
        }
        return "";
    }

    protected static String getSegmentValue(String segment, int fieldIndex, int componentIndex) {
        if (segment == null || segment.isEmpty() || fieldIndex <= 0) {
            return "";
        }

        String[] fields = segment.split("\\|", -1);
        int idx = fieldIndex;
        if (idx >= fields.length) {
            return "";
        }

        String field = fields[idx];
        if (componentIndex > 0) {
            String[] components = field.split("\\^", -1);
            int compIdx = componentIndex - 1;
            if (compIdx < 0 || compIdx >= components.length) {
                return "";
            }
            return components[compIdx];
        }
        return field;
    }

    protected static int[] parseFieldComponent(String path) {
        if (path == null || path.isEmpty()) {
            return new int[] {0, 0};
        }
        String[] parts = path.split("-");
        int fieldIndex = parseIntSafe(parts[0]);
        int componentIndex = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        return new int[] {fieldIndex, componentIndex};
    }

    protected static int parseIntSafe(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected static boolean isValidNumericObservation(
        String valueType,
        String paramValue,
        String abnormalFlag,
        String resultStatus
    ) {
        if (paramValue == null || paramValue.isBlank()) {
            return false;
        }
        if ("INV".equalsIgnoreCase(safeTrim(abnormalFlag)) || "X".equalsIgnoreCase(safeTrim(resultStatus))) {
            return false;
        }
        String type = safeTrim(valueType);
        if (!type.isEmpty() && !"NM".equalsIgnoreCase(type) && !"SN".equalsIgnoreCase(type)) {
            return false;
        }
        try {
            new java.math.BigDecimal(paramValue.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    protected final DriverContext context;

    private final FrameDecoder frameDecoder;
    private final ProtocolSession protocolSession;

    private java.util.Map<String, String> defaultObservationAttributes() {
        if (context.departmentId() == null || context.departmentId().isBlank()) {
            return java.util.Map.of();
        }
        return java.util.Map.of(ObservationPublisher.ATTR_DEPARTMENT_ID, context.departmentId());
    }

    private final class Hl7MllpProtocolSession implements ProtocolSession {
        @Override
        public void onFrame(Frame frame, ProtocolSessionContext sessionContext) {
            String er7 = normalizeEr7(new String(frame.copyPayload(), charset()));
            String messageType = normalizeMessageType(er7).orElse("");
            String messageControlId = normalizeMessageControlId(er7).orElse("");

            if (messageType == null || messageType.isBlank()) {
                log.warn("Missing MSH-9 message type in HL7 payload: msgId={}", messageControlId);
            }

            try {
                if (!shouldProcessMessage(messageType, er7)) {
                    log.info("Skip HL7 message: msgType={} msgId={} driver={} ip={}",
                        messageType, messageControlId, driverCode(), context.remoteIp());
                    return;
                }

                Optional<ObservationBatch> batch = mapEr7ToObservationBatch(er7, sessionContext, messageControlId);
                batch.ifPresent(sessionContext.publisher()::publishDeviceData);
            } catch (Exception e) {
                log.warn("HL7 processing error: {} msgType={} msgId={} driver={} ip={}",
                    e.toString(), messageType, messageControlId, driverCode(), context.remoteIp());
            } finally {
                buildAck(messageControlId).ifPresent(bytes -> sessionContext.send(bytes, ackDelayMs()));
            }
        }
    }
}

