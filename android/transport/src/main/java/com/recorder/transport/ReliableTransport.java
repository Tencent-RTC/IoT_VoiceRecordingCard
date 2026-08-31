package com.recorder.transport;

/**
 * 传输层对业务层的唯一接口。
 *
 * <p>业务层<b>只</b>看到：send / onMessageReceived / onLinkDown
 * + 两个标量（maxMessageSize、streamingCapable）
 * + 一个二值就绪信号。
 *
 * <p>不暴露 MTU、分片、帧序号、ACK、队列深度等任何内部机制，
 * 满足 {@code business protocol/协议约定.md} §2.3 的透明性要求。
 */
public interface ReliableTransport {
    /** 业务层启动可靠传输 */
    void start();

    /** 业务层主动断开连接。 */
    void stop();

    /**
     * 提交一条完整业务消息。传输层负责排队、分片、seq、流控。
     *
     * <p>不会因窗口占满而失败；仅在超过 {@link #getMaxMessageSize()} 时
     * 回调 {@code onError}。接纳即负责到底，调用方无需持有并重试。
     */
    void send(byte[] message);

    /**
     * 传输层当前是否愿意接纳新消息。
     *
     * <p>仅供「数据源型」生产者节流使用（如实时推送音频帧、文件传输）。
     * 返回 false 时调用方应暂缓<b>生成</b>下一条消息，
     * 而不是持有并重试某条被拒消息 —— send() 从不拒收。
     */
    boolean isReadyToSend();

    /**
     * 本端发送方向是否因 ACK 长时间无推进而处于软停滞。
     * 软停滞是指因为弱网环境等原因，暂时无法发送新的数据（在协议底层实现中，即指一段时间未收到 ACK，导致发送窗口暂时无法推进），
     * 但尚不至于使得系统断开蓝牙 GATT 连接、且未来可能恢复的中间过渡状态。
     *
     * <p>软停滞期间连接仍为 READY（底层 GATT 连接尚未断开），已接纳消息及序号状态均被保留；
     * {@link #isReadyToSend()} 返回 false。收到推进窗口的 ACK 后自动恢复。
     */
    boolean isSendStalled();

    /** 传输层链路可用，可以正常收发业务报文 */
    boolean isReady();

    /**
     * 获取传输协议层最大能容纳的业务层报文（message）的大小。
     * 
     * 虽然传输协议层已经帮助屏蔽了底层分片等实现细节，但显然其内部的发送/接收缓冲区不可能无限大，
     * 因此仍然需要对业务报文的大小进行限制。
     * 业务层代码调研本方法即可获得具体的限制值。
     */
    int getMaxMessageSize();

    /** 
     * 当前连接能否承载流式二进制数据传输 。
     * 
     * 如果底层 MTU 协商的结果小于预设阈值，就会返回 false。
     * */
    boolean isStreamingCapable();

    /** 
     * 获取传输层内部状态信息 
     * 
     * 现阶段<p>仅供调试使用</p>，业务代码不应该依赖于该方法返回的任何信息。
     */
    TransportMetrics getMetrics();

    /** 供业务层代码在初始化传输层时，安装回调函数 */
    void setCallback(Callback callback);

    interface Callback {

        /** 握手完成，可开始发送业务消息。 */
        void onReady(int maxMessageSize, boolean streamingCapable);

        /** 
         * 收到一条新的业务报文。此报文完整、已通过校验。
         * 
         * 传输协议保证按 FIFO 顺序交付报文。
         * 同一报文只会触发一次该回调。
         */
        void onMessageReceived(byte[] message);

        /**
         * 由「不可接纳」转为「可接纳」时回调一次（边沿触发），
         * 用于通知业务代码可继续发送报文。
         * 
         * 与 {@link #isReadyToSend()} 的电平查询配合使用，避免漏掉边沿。
         */
        void onReadyToSend();

        /** 本端发送方向进入软停滞状态，稍后可能恢复。 */
        default void onSendStalled(long stalledForMs) {
        }

        /** 本端发送方向已从软停滞恢复。 */
        default void onSendResumed(long stalledForMs) {
        }

        /** GATT 链路已终止；此后不再有任何 onMessageReceived。 */
        void onLinkDown(TransportFatalError reason, String message);

        /** 传输层发生非致命本地错误，链路仍然可用。 */
        void onError(TransportError error, String message);
    }
}
