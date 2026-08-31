package com.recorder.transport;

/** DATA 帧 flags 位定义（协议文档 §4.3）。 */
public final class DataFlags {

    /** 某条业务消息的首帧。 */
    public static final int SOM = 0x1;

    /** 某条业务消息的末帧；该帧尾部附带 2 字节整消息 CRC16。 */
    public static final int EOM = 0x2;

    /** 保留位，必须为 0；非 0 即 PROTOCOL_VIOLATION。 */
    public static final int RESERVED_MASK = 0xC;

    private DataFlags() {
    }
}
