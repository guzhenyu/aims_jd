package com.jingyicare.aims_jd.utils;

import java.nio.charset.*;

public class Consts {
    /**
     * 默认时区
     */
    public static final String ZONE_ID = "Asia/Shanghai";

    /**
     * TCP连接，消息队列设置
     */
    public static final int CHANNEL_CONNECT_TIMEOUT_SECS = 300;  // 连接超时，秒
    public static final int CHANNEL_READ_BUFFER_SIZE = 8192;
    public static final int MAX_BUFFER_BYTES = 1024 * 1024;  // 1 MB
    public static final int BYTES_LEFT_FOR_TRUNCATE = 1024;  // 最多保留最后 1 KB
    public static final int MESSAGE_QUEUE_SIZE = 1000;  // 消息队列大小
    

    /**
     * 设备连接心跳间隔（毫秒）
     */
    public static final int DEVICE_HEARTBEAT_INTERVAL_SECS = 1;
    public static final int DEVICE_HEARTBEAT_TRY_LOCK_MS = 100;
    public static final long LOST_CONN_WAIT_NS = 300L * 1_000_000_000L;  // 300秒

    /**
     * 字符编码常量
     */
    public static final Charset CHARSET = StandardCharsets.UTF_8;

    /**
     * HL7 常量
     */
    public static final String HL7_VER = "2.6";

    /**
     * ASCII常量
     */
    public static final byte SOH = 0x01;  // <SOH> 1
    public static final byte STX = 0x02;  // <STX> 2 - start of text
    public static final byte ETX = 0x03;  // <ETX> 3 - end of text
    public static final byte EOT = 0x04;  // <EOT> 4 - end of transmission
    public static final byte ENQ = 0x05;  // <ENQ> 5 - enquiry
    public static final byte ACK = 0x06;  // <ACK> 6 - acknowledge
    public static final byte LF = 0x0A;   // <LF>  10
    public static final byte VT = 0x0B;   // <VT>  11
    public static final byte CR = 0x0D;   // <CR>  13
    public static final byte ETB = 0x17;  // <ETB> 23 - end of transmission block
    public static final byte ESC = 0x1B;  // <ESC> 27
    public static final byte FS = 0x1C;   // <FS>  28

    /**
     * 雷度常量
     */

    /**
     * 通用常量
     */
    public static final Integer GENDER_MALE = 1;
    public static final Integer GENDER_FEMALE = 0;
    public static final Integer GENDER_UNKNOWN = 4;
}
