package com.recorder.transport;

/**
 * 致命错误：一律导致链路终止。
 *
 * <p>传输层对业务层的承诺是二元的：要么按序、完整、不重复地交付，
 * 要么进入 LINK_DOWN 且此后不再交付任何消息。
 * <b>绝不允许「丢弃某条消息后继续运行」</b>——那会在有序流中留下空洞。
 */
public enum TransportFatalError {

    /** 检测到 seq 缺口。本实验版无重传，故直接拆链。★ 实验主指标 */
    FRAME_LOSS,

    /** 收到 seq < expectedRxSeq 的帧。无重传机制时理论上不应出现。★ 实验指标 */
    UNEXPECTED_DUPLICATE,

    /**
     * 旧版：发送窗口在 ackStallTimeoutMs 内无推进。
     * 当前版本已将该情况改为可恢复的软停滞，保留枚举值用于历史指标兼容。
     */
    ACK_STALL,

    VERSION_MISMATCH,
    LINK_SETUP_FAILED,

    /** 重组消息 CRC16 不匹配。通常指向我方分片/重组代码 bug。 */
    CRC_MISMATCH,

    /** SOM/EOM 违规、空载荷 DATA、保留位非 0、未知或保留帧类型、帧长不符。 */
    PROTOCOL_VIOLATION,

    MESSAGE_TOO_LARGE_RX,

    /** 本端 TX 队列溢出：数据源无视了就绪信号。 */
    TX_QUEUE_OVERFLOW,

    LOCAL_WRITE_FAILED,
    GATT_DISCONNECTED,
    CLOSED_BY_LOCAL
}
