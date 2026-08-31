package com.recorder.transport;

/**
 * 帧类型。编号严格遵循协议文档 §4.2。
 *
 * <p>0x4 (NACK)、0x5 (PING)、0x6 (PONG)、0x8 (LINK_RESET) 在本实验版中
 * <b>保留但不实现</b>：收到即视为 PROTOCOL_VIOLATION 并拆链，
 * 目的是让「一端有重传、另一端不认识 NACK」的版本错配在建链阶段就暴露，
 * 而不是变成难以定位的间歇故障。
 */
public enum FrameType {

    LINK_SETUP(0x1),
    DATA(0x2),
    ACK(0x3),
    LINK_SETUP_ACK(0x7);

    public final int value;

    FrameType(int value) {
        this.value = value;
    }

    public static FrameType fromValue(int value) {
        switch (value) {
            case 0x1:
                return LINK_SETUP;
            case 0x2:
                return DATA;
            case 0x3:
                return ACK;
            case 0x7:
                return LINK_SETUP_ACK;
            default:
                return null;
        }
    }
}
