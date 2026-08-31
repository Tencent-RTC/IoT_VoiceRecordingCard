package com.recorder.transport;

/** 传输层配置。协议文档 §6.3；方向相关参数见 §6.1。 */
public final class TransportConfig {

    /** 5 = 反向控制业务协议版；不同 wire schema 的实现必须在建链时互斥。 */
    public int protocolVersion = 5;

    /**
     * 首选 MTU。
     *
     * <p><b>247 是实测验证过的稳定值</b>（attPayload=244）。
     *
     * <p>曾尝试提到 517 以减半帧数，但实测某些机型的 GATT Server 在 Notify
     * 帧长超过约 250 字节时会持续本地拒收 —— 旧版布尔 API 不返回错误码，
     * 只表现为 {@code notifyCharacteristicChanged} 恒返回 false，
     * 发送完全停滞并最终进入 ACK 软停滞。
     *
     * <p>教训：Notify 的实际可承载长度<b>不等于</b>协商出的 MTU。
     * 提升吞吐应优先依赖 2M PHY 与窗口，而非无限抬高 MTU。
     * 传输层会把实际 DATA 帧承载固定封顶为 244 B；即使协商到更大 MTU，
     * 也不再做运行时试探或动态缩小分片。
     */
    public int preferredMtu = 247;
    public int fallbackMtu = 23;

    /** attPayload 低于该值时不具备实时音频流能力（本 Demo 仅用于上报，不阻塞文件传输）。 */
    public int minAttPayloadForStreaming = 100;

    /** 最新累计 ACK 的快速补发间隔。每次确认成功后先快速补发 3 次。 */
    public int ackRepeatMs = 200;

    /**
     * 快速补发额度耗尽后，最新累计 ACK 的低频保活间隔。
     *
     * <p>只要 GATT 仍连接就持续低频补发，使「窗口已耗尽、用户稍后重新靠近」
     * 仍有新的 ACK 可以打破死锁。线上仍是同一种 ACK 帧，不新增帧类型。
     */
    public int ackKeepAliveMs = 1000;

    /**
     * 发送窗口已满、仍有数据等待，且在该时长内无推进时标记为软停滞。
     *
     * <p>取 3000ms 而非文档初值 1000ms：ACK 与 DATA 共用同一条 BLE 写通道，
     * 双向满负荷传输时 ACK 可能连续多次被本地拒收；Android 在后台/息屏
     * 或降低连接优先级时抖动可达数百毫秒。1000ms 余量不足，会把
     * 「暂时拥塞」误判为「链路不可用」。
     *
     * <p>软停滞不清空队列、不终止业务连接；后续 ACK 真正推进时自动恢复。
     * 该值只影响弱链路状态的发现速度，不影响正常吞吐。
     */
    public int ackStallTimeoutMs = 3000;

    public int linkSetupTimeoutMs = 500;
    public int linkSetupMaxRetry = 3;

    /**
     * TX 队列容量。取小值以约束控制 Response 的排空延迟（协议文档 §10.7）。
     *
     * <p>溢出为致命错误：说明数据源生产者无视了就绪信号，属实现 bug。
     */
    public int txQueueMaxMessages = 4;

    // ---- 方向相关（协议文档 §6.1）----

    /** 本端可接收的单条业务消息上限，也即重组缓冲大小。 */
    public int rxMaxMessageSize;

    /** 本端可发送的单条业务消息上限。 */
    public int txMaxMessageSize;

    /**
     * 本端接收窗口，作为 credit 授予对端。
     *
     * <p>12 → 32 是为连接间隔与 worker 调度抖动保留余量。ACK 虽在 DATA
     * 处理完成后立即提交，但连接间隔抖动（尤其后台/息屏）仍会拉长往返，
     * 窗口偏小时发送方会频繁停在 WINDOW_BLOCKED。
     * 在固定 244 B 帧上限下，32 帧在途约 7.6 KB，接收缓冲代价仍可接受
     * （credit 为 uint8，上限 255）。
     */
    public int rxWindow;

    /**
     * 对端声明的接收窗口，即本端的初始发送窗口。
     *
     * <p>Lite 版不做参数协商（两端代码同一团队维护），因此这里写死，
     * 必须与对端的 {@link #rxWindow} 严格一致。
     */
    public int peerRxWindow;

    private TransportConfig() {
    }

    /**
     * 设备（mock server）侧：大流量方向为「设备 → App」。
     */
    public static TransportConfig forDevice() {
        TransportConfig c = new TransportConfig();
        c.txMaxMessageSize = 4608;   // File Chunk 序列化约 4118 B，留余量
        c.rxMaxMessageSize = 512;    // App 的 Request 最大约 60 B
        c.rxWindow = 8;              // App → 设备 只有小 Request
        c.peerRxWindow = 32;         // 设备 → App 为大流量方向
        return c;
    }

    /**
     * App（client）侧：与 {@link #forDevice()} 严格镜像对称。
     */
    public static TransportConfig forApp() {
        TransportConfig c = new TransportConfig();
        c.txMaxMessageSize = 512;
        c.rxMaxMessageSize = 4608;
        c.rxWindow = 32;
        c.peerRxWindow = 8;
        return c;
    }

    void validate() {
        if (ackRepeatMs <= 0) {
            throw new IllegalStateException("ackRepeatMs 必须大于 0");
        }
        if (ackKeepAliveMs < ackRepeatMs) {
            throw new IllegalStateException("ackKeepAliveMs 必须 >= ackRepeatMs");
        }
        if (ackStallTimeoutMs < 3L * ackRepeatMs) {
            throw new IllegalStateException(
                    "ackStallTimeoutMs 必须 >= 3 * ackRepeatMs，否则快速 ACK 补发尚未完成");
        }
        if (rxWindow <= 0 || rxWindow > 255) {
            throw new IllegalStateException("rxWindow 必须在 1..255（credit 为 uint8）");
        }
        if (peerRxWindow <= 0 || peerRxWindow > 255) {
            throw new IllegalStateException("peerRxWindow 必须在 1..255");
        }
    }
}
