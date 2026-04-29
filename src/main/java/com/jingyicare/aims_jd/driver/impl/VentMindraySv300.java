package com.jingyicare.aims_jd.driver.impl;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.driver.api.DriverContext;
import com.jingyicare.aims_jd.driver.api.DriverPlugin;
import com.jingyicare.aims_jd.driver.frame.FrameDecoder;
import com.jingyicare.aims_jd.driver.frame.Sv300FrameDecoder;
import com.jingyicare.aims_jd.driver.impl.base.AbstractSingleDeviceProtocolDriver;
import com.jingyicare.aims_jd.driver.model.Frame;
import com.jingyicare.aims_jd.driver.model.ObservationValue;
import com.jingyicare.aims_jd.driver.registry.DriverPluginFactory;
import com.jingyicare.aims_jd.driver.session.ProtocolSession;
import com.jingyicare.aims_jd.driver.session.ProtocolSessionContext;
import com.jingyicare.aims_jd.tool.TxtDumper;

/**
 * Mindray SV300 单设备驱动。
 * 第二阶段迁移后改为 DriverPlugin + SessionRuntime。
 */
@Slf4j
public class VentMindraySv300 extends AbstractSingleDeviceProtocolDriver {
    public static final String DRIVER_CODE = "jd_vent_mindray_sv300";
    private static final long SETTINGS_INTERVAL_NS = 10L * 60L * 1_000_000_000L;
    private static final long CMD_DELAY_MS = 5L;

    public VentMindraySv300(DriverContext context) {
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

    private final class Sv300ProtocolSession implements ProtocolSession {
        @Override
        public void onFrame(Frame frame, ProtocolSessionContext sessionContext) {
            byte[] bytes = frame.copyPayload();
            if (bytes.length <= 5) {
                return;
            }

            String recordedAtIso8601 = nowIso8601Utc();
            byte cmd = bytes[1];
            int idx = 2;

            List<ObservationValue> values = new ArrayList<>();
            StringBuilder dumpBuilder = new StringBuilder()
                .append("=== SV300 Frame ").append(driverCode()).append(" ===\n")
                .append(TxtDumper.bytesToString(bytes))
                .append('\n');

            while (idx + 5 < bytes.length - 3) {
                String obCode = getObCode(cmd, bytes[idx]);
                String value = new String(bytes, idx + 1, 5, context.charset()).trim();
                String paramCode = context.paramMap().get(obCode);
                if (paramCode != null) {
                    values.add(new ObservationValue(paramCode, value, recordedAtIso8601));
                }
                dumpDebug(sessionContext,
                    "(" + cmd + ") obCode=" + obCode + " paramCode=" + paramCode + " value=" + value);
                idx += 6;
            }

            if (!values.isEmpty()) {
                publishObservationBatch(sessionContext, values, TxtDumper.bytesToString(bytes));
                dumpBuilder.append("\n=== mapped values ===\n");
                for (ObservationValue value : values) {
                    dumpBuilder.append(value.paramCode())
                        .append('=')
                        .append(value.recordedStr())
                        .append(" @ ")
                        .append(value.recordedAtIso8601())
                        .append('\n');
                }
            }
            dumpText(sessionContext, dumpBuilder.toString());
        }

        @Override
        public void onTick(ProtocolSessionContext sessionContext) {
            long nowNs = System.nanoTime();
            if (nowNs - lastGetSettingsAtNs > SETTINGS_INTERVAL_NS) {
                sessionContext.send(getSettingsCmd, CMD_DELAY_MS);
                lastGetSettingsAtNs = nowNs;
            } else {
                sessionContext.send(getMeasuresCmd, CMD_DELAY_MS);
            }
        }
    }

    public static final class Factory implements DriverPluginFactory {
        @Override
        public String driverCode() {
            return DRIVER_CODE;
        }

        @Override
        public DriverPlugin create(DriverContext context) {
            return new VentMindraySv300(context);
        }
    }

    private long lastGetSettingsAtNs = 0L;

    private final FrameDecoder frameDecoder = new Sv300FrameDecoder();
    private final ProtocolSession protocolSession = new Sv300ProtocolSession();

    private final byte[] getSettingsCmd = new byte[] {
        (byte) 0x1B, (byte) 0x24, (byte) 0x33, (byte) 0x46, (byte) 0x0D
    };
    private final byte[] getMeasuresCmd = new byte[] {
        (byte) 0x1B, (byte) 0x25, (byte) 0x34, (byte) 0x30, (byte) 0x0D
    };

    static String getObCode(byte cmd, byte obCodeByte) {
        int code = obCodeByte & 0xFF;
        if (cmd == 0x24) {
            switch (code) {
                case 0x20: return "st_Insp%";
                case 0x21: return "st_Exp%";
                case 0x22: return "st_TriggerFlow";
                case 0x23: return "st_TriggerPressure";
                case 0x24: return "st_TV";
                case 0x25: return "st_f";
                case 0x26: return "st_fSIMV";
                case 0x27: return "st_PEEP";
                case 0x28: return "st_Plimit";
                case 0x29: return "st_Pinsp";
                case 0x2A: return "st_DeltaPsupp";
                case 0x2B: return "st_Tinsp";
                case 0x2C: return "st_Tslope";
                case 0x2D: return "st_ExpTrigger";
                case 0x2E: return "st_O2%";
                case 0x2F: return "st_Phigh";
                case 0x30: return "st_Plow";
                case 0x31: return "st_Thigh";
                case 0x32: return "st_Tlow";
                case 0x33: return "st_Flow";
                case 0x34: return "st_DeltaPapnea";
                case 0x35: return "st_fapnea";
                case 0x36: return "st_DeltaIntPEEP";
                case 0x37: return "st_Sign";
                case 0x38: return "st_Waveform";
                case 0x39: return "st_TI";
                case 0x3A: return "st_Min_Rate";
                case 0x3B: return "st_Trig%";
                case 0x3C: return "st_Assist";
                case 0x3D: return "st_IBW";
                case 0x3E: return "st_TVG";
                case 0x3F: return "st_PlimVG";
                case 0x40: return "st_ApneaTi";
                case 0x41: return "st_PS";
                case 0x42: return "st_O2Flow";
                case 0x43: return "st_N2OFlow";
                case 0x44: return "st_AIRFlow";
                case 0x45: return "st_ExpTriggerAuto";
                case 0x46: return "st_ATCswitch";
                case 0x47: return "st_ATCExpSwitch";
                case 0x48: return "st_ATCIntubationType";
                case 0x49: return "st_ATCTubeSize";
                case 0x4A: return "st_ATCCompensation";
                case 0x4B: return "st_SighInterval";
                case 0x4C: return "st_SighCycle";
                case 0x4D: return "st_ApneaVentSwitch";
                case 0x4E: return "st_TvApnea";
                case 0x4F: return "st_Tpause";
                case 0x50: return "st_ApneaInsp%";
                case 0x51: return "st_ApneaExp%";
                case 0x52: return "st_RM_DeltaP";
                case 0x53: return "st_Step";
                case 0x54: return "st_Breath";
                case 0x55: return "st_DeltaPinsp";
                case 0x56: return "st_MV%";
                case 0x57: return "st_Ti_max";
                case 0x58: return "st_DeltaPmanInsp";
                case 0x59: return "st_TmanInsp";
                case 0x5A: return "st_PressureHold";
                case 0x5B: return "st_HoldTime";
                default: return String.format("0x%02X", code);
            }
        } else if (cmd == 0x25) {
            switch (code) {
                case 0x20: return "Insp%";
                case 0x21: return "Exp%";
                case 0x22: return "Ppeak";
                case 0x23: return "Pmean";
                case 0x24: return "Pplat";
                case 0x25: return "PEEP";
                case 0x26: return "MVe";
                case 0x27: return "MVspn";
                case 0x28: return "MVLeak";
                case 0x29: return "TVe";
                case 0x2A: return "TVi";
                case 0x2B: return "TVespn";
                case 0x2C: return "Rate";
                case 0x2D: return "Ftotal";
                case 0x2E: return "Fman";
                case 0x2F: return "Fspn";
                case 0x30: return "R";
                case 0x31: return "Ri";
                case 0x32: return "Re";
                case 0x33: return "RSBI";
                case 0x34: return "C";
                case 0x35: return "Cstat";
                case 0x36: return "Cdyn";
                case 0x37: return "WOB";
                case 0x38: return "FiO2";
                case 0x39: return "EtO2";
                case 0x3A: return "O2Flow";
                case 0x3B: return "N2OFlow";
                case 0x3C: return "AIRFlow";
                case 0x3D: return "EtCO2";
                case 0x3E: return "FiCO2";
                case 0x3F: return "EtN2O";
                case 0x40: return "FiN2O";
                case 0x41: return "EtAA";
                case 0x42: return "FiAA";
                case 0x43: return "EtHal";
                case 0x44: return "FiHal";
                case 0x45: return "EtEnf";
                case 0x46: return "FiEnf";
                case 0x47: return "EtIso";
                case 0x48: return "FiIso";
                case 0x49: return "EtSev";
                case 0x4A: return "FiSev";
                case 0x4B: return "EtDes";
                case 0x4C: return "FiDes";
                case 0x4D: return "MAC";
                case 0x4E: return "BIS";
                case 0x4F: return "SQI";
                case 0x50: return "EMG";
                case 0x51: return "SR";
                case 0x52: return "SEF";
                case 0x53: return "TP";
                case 0x54: return "BC";
                case 0x55: return "UsageHal";
                case 0x56: return "UsageEnf";
                case 0x57: return "UsageIso";
                case 0x58: return "UsageSev";
                case 0x59: return "UsageDes";
                case 0x5A: return "Rate";
                case 0x5B: return "Rcexp";
                case 0x5C: return "Tve/IBW";
                case 0x5D: return "VDaw";
                case 0x5E: return "Vdaw/Tve";
                case 0x5F: return "Vtalv";
                case 0x60: return "slopCO2";
                case 0x61: return "V_CO2";
                case 0x62: return "VeCO2";
                case 0x63: return "ViCO2";
                case 0x64: return "V_alv";
                case 0x65: return "SPO2";
                case 0x66: return "PR";
                case 0x67: return "O2%";
                case 0x68: return "Flow";
                case 0x69: return "BIS_L";
                case 0x6A: return "BIS_R";
                case 0x6B: return "SBIS_L";
                case 0x6C: return "SBIS_R";
                case 0x6D: return "SQI_L";
                case 0x6E: return "SQI_R";
                case 0x6F: return "EMG_L";
                case 0x70: return "EMG_R";
                case 0x71: return "SEMG_L";
                case 0x72: return "SEMG_R";
                case 0x73: return "SR_L";
                case 0x74: return "SR_R";
                case 0x75: return "SEF_L";
                case 0x76: return "SEF_R";
                case 0x77: return "TP_L";
                case 0x78: return "TP_R";
                case 0x79: return "BC_L";
                case 0x8A: return "BC_R";
                case 0x8B: return "ASYM";
                case 0x8C: return "TOF_Ratio";
                case 0x8D: return "TOF_Count";
                case 0x8E: return "DBS_Ratio";
                case 0x8F: return "DBS_Count";
                case 0x90: return "ST_Ratio";
                case 0x91: return "ST_Count";
                case 0x92: return "PTC";
                case 0x93: return "T1";
                case 0x94: return "ConsumeSpeedHal";
                case 0x95: return "ConsumeSpeedEnf";
                case 0x96: return "ConsumeSpeedIso";
                case 0x97: return "ConsumeSpeedSev";
                case 0x98: return "ConsumeSpeedDes";
                case 0x99: return "Tinsp";
                case 0x9A: return "WOBpat";
                case 0x9B: return "WOBtot";
                case 0x9C: return "WOBimp";
                case 0x9D: return "PIF";
                case 0x9E: return "PEF";
                case 0x9F: return "EEF";
                case 0xA0: return "Stress_Index";
                case 0xA1: return "C20/C";
                case 0xA2: return "MVi";
                case 0xA3: return "step";
                default: return String.format("0x%02X", code);
            }
        } else {
            return String.format("0x%02X", code);
        }
    }
}

