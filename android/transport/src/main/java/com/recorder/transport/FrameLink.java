package com.recorder.transport;

/**
 * 帧链路：传输层与具体 BLE 实现之间的边界。
 *
 * <p>传输层<b>不</b>感知 BluetoothGatt / GattServer、特征值、权限、回调线程。
 * client 侧用 Write Without Response 实现，server 侧用 Notify 实现，
 * 二者对 {@link ReliableTransport} 完全等价，从而使传输层代码在两端复用。
 */
public interface FrameLink {

    /**
     * 提交一帧。返回 false 表示本地立即拒绝，调用方应等待
     * {@link Callback#onFrameWriteComplete()} 后重试。
     */
    boolean writeFrame(byte[] frame);

    /** 本地发送队列当前是否可接纳新帧，用于推进发送状态机。 */
    boolean canAcceptWrite();

    /** 当前 ATT 可用 characteristic value 长度 = MTU - 3。 */
    int getAttPayloadSize();

    /** 由 BLE 层调用：连接是否处于可收发帧的状态。 */
    boolean isLinkConnected();

    /**
     * 主动断开当前物理连接。
     *
     * <p>传输层进入不可恢复的 DOWN 后必须调用，使对端也能通过底层断开
     * 回调结束当前业务会话。设备侧实现只断开当前 peer，不停止 GATT Server
     * 或广播；重复调用必须安全。
     */
    void disconnect();

    void setCallback(Callback callback);

    interface Callback {

        /** 底层链路就绪（服务/特征/Notify 均已可用），可以开始握手。 */
        void onFrameLinkReady();

        /** 收到一帧原始字节。 */
        void onFrameReceived(byte[] frame);

        /**
         * 本地发送已完成、可继续推进。
         * 仅表示本地允许继续发送，<b>不</b>表示远端已收到。
         */
        void onFrameWriteComplete();

        /** 底层链路断开或出现不可恢复错误。 */
        void onFrameLinkDown(String reason);
    }
}
