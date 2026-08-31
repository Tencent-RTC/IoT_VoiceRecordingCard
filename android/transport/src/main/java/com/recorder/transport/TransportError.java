package com.recorder.transport;

/** 非致命错误：链路继续可用。 */
public enum TransportError {

    /** 链路尚未 READY。 */
    NOT_READY,

    /** 业务层提交了超过 txMaxMessageSize 的消息，属业务层 bug。 */
    MESSAGE_TOO_LARGE_TX,

    ENCODE_FAILED
}
