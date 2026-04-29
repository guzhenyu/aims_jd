package com.jingyicare.aims_jd.utils;

import java.io.IOException;
import java.util.*;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;

import com.jingyicare.jingyi_aims_engine.proto.config.AimsHardware.*;

@Slf4j
public class ProtoUtils {
    public static String protoToJson(MessageOrBuilder msg) {
        try {
            return jsonPrinter.print(msg);
        } catch (Exception e) {
            log.error("Failed to convert proto to string: ", e, "\n", e.getStackTrace());
            return null;
        }
    }

    public static String protoToTxt(MessageOrBuilder msg) {
        if (msg == null) {
            return null;
        }
        return TextFormat.printer().printToString(msg);
    }

    public static DevObservationConfigsPB txtToProto(String txt) {
        if (txt == null) {
            return null;
        }
        DevObservationConfigsPB.Builder builder = DevObservationConfigsPB.newBuilder();
        try {
            TextFormat.merge(txt, builder);
            return builder.build();
        } catch (TextFormat.ParseException e) {
            log.error("Failed to convert text to proto: {}", e.getMessage(), e);
            return null;
        }
    }

    private static final Set<Descriptors.FieldDescriptor> fields = new HashSet<>();
    private static final JsonFormat.Printer jsonPrinter;
    static {
        // 新增必须打印出来的字段（默认值也需要打印）
        // ReturnCode.code
        // fields.add(ReturnCode.getDescriptor().findFieldByNumber(1));

        jsonPrinter = JsonFormat.printer()
            //.includingDefaultValueFields(fields)
            .printingEnumsAsInts();
    }
}
