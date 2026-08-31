package com.recorder.transport;

import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lite-WithoutRetry 传输层实现。
 *
 * <p>机制组合（协议文档 §1.2）：
 * <pre>
 * 信用流控     → 从源头防止接收侧缓冲溢出（唯一防线）
 * 帧级 seq     → 检测缺口（BLE LL 保证有序，故一个缺口 = 真实丢包）
 * 缺口即拆链   → 本版无 DATA 重传；绝不在有序流中留下空洞
 * 立即累计 ACK  → 每处理完一个 DATA 就确认；先快速补发、再低频保活
 * ACK 软停滞   → 窗口长期无推进时暂停发送；保留状态并等待恢复
 * </pre>
 *
 * <p><b>线程模型</b>：全部状态机、编解码、定时器、重组均在单一 worker 线程上执行，
 * 因此内部状态无需加锁。所有公开方法都把工作 post 到该线程。
 * 业务回调也在该线程触发，由调用方自行切到 UI 线程。
 *
 * <p><b>发送驱动</b>：主驱动是 {@code onFrameWriteComplete()}（BLE 写完成回调），
 * 每完成一帧立即推进下一帧，使吞吐由链路能力决定而非定时器周期。
 * {@link #TICK_MS} 的周期 tick 只作兜底（本地拒收、回调丢失、ACK 计时），
 * 不承担正常路径的发送节奏 —— 否则速率会被硬限制在
 * {@code 1000 / TICK_MS} 帧每秒。
 */
public final class DefaultReliableTransport implements ReliableTransport, FrameLink.Callback {

    /** 兜底 tick 周期。正常发送由写完成回调驱动，不依赖它。 */
    private static final int TICK_MS = 5;

    /** 每个最新累计 ACK 成功提交后的快速补发次数。 */
    private static final int ACK_REPEAT_LIMIT = 3;

    /**
     * 线上 DATA 帧的固定承载上限。MTU 可以协商得更大，但协议实现不再动态试探；
     * 244 B（MTU 247）是双机实测稳定值。
     */
    private static final int MAX_EFFECTIVE_ATT_PAYLOAD = 244;

    /**
     * IDLE：底层 GATT 链路尚未就绪，还没有开始任何握手动作。
     * SETUP_WAIT：底层链路已就绪，正在完成传输层握手。
     * READY： 链路可用，可以收发业务层报文。
     * DOWN：连接断开。该状态是终态，不会自动复活。
     * 
     * 状态迁移图示：
     * IDLE ──(底层链路就绪)──> SETUP_WAIT ──(握手成功)──> READY ──(致命错误/主动停止)──> DOWN
     *                     │                                            ▲
     *                     └──────(握手超时/版本不匹配)────────────────────┘
     * DOWN ──(restartForNewConnection)──> IDLE（仅供重连）
     */
    private enum LinkState { IDLE, SETUP_WAIT, READY, DOWN }

    private final FrameLink link;
    private final TransportConfig config;
    private final boolean initiator;
    private final TransportMetrics metrics = new TransportMetrics();
    private final ScheduledExecutorService worker;
    private final Logger logger;

    private Callback callback;

    /** 权威状态，只在 worker 线程读写。 */
    private LinkState state = LinkState.IDLE;

    /**
     * {@link #state} 的线程安全镜像，供 {@code isReady()} / {@code isReadyToSend()}
     * 在任意线程读取。所有状态变更必须经 {@link #setState} 同时更新两者。
     */
    private final AtomicReference<LinkState> stateRef = new AtomicReference<>(LinkState.IDLE);

    private int frozenAttPayload;
    private long linkUpAtMs;

    // ---- 发送侧 ----
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();

    /**
     * 已被 {@link #send} 接纳、但尚未发送完毕的业务消息数。
     *
     * <p><b>必须在调用线程同步递增</b>，而不是等 worker 线程入队后才更新。
     * 否则 {@link #isReadyToSend()} 返回的是过期快照：调用方在紧凑循环里
     * 连续读到「可发送」，瞬间把成千上万条消息 post 进 worker，
     * 必然触发 TX_QUEUE_OVERFLOW —— 而这并非调用方的错。
     *
     * <p>递增点：{@code send()} 进入时（调用线程）。
     * 递减点：一条消息全部帧发出后、被拒收时、清空缓冲时（worker 线程）。
     */
    private final AtomicInteger outstanding = new AtomicInteger();

    private int nextTxSeq;
    private int lastAckSeq;
    private int peerCredit;
    private byte[] curMsg;
    private int curOff;
    private int curMsgCrc;
    private byte[] pendingFrame;         // 已分配 seq 但本地写被拒，待重试
    private boolean lastReadyToSend = true;

    /** 防止 onReadyToSend 回调内的 send() 递归重入。 */
    private boolean inReadyCallback;

    /**
     * 粘性通知标志：释放过名额后置位，通知成功后清除。
     * 使「可以继续生产」的通知不会因跨线程时序错位而丢失。
     */
    private boolean needNotify;

    /**
     * 发送窗口「由空转为非空」的时刻；窗口为空时为 0。
     *
     * <p>ackStall 只度量「窗口已满、仍有 DATA 等待且无推进」的时长。
     * 若改用「距上次 ACK 推进」的时长，则链路空闲后发出的首帧会被
     * 立即误判为停滞。
     */
    private long stallSinceMs;
    private long windowBlockedSinceMs;

    /** ACK 长时间无推进只暂停本端发送，不终止连接或清空任何状态。 */
    private boolean sendStalled;
    private final AtomicBoolean sendStalledRef = new AtomicBoolean();

    // ---- 接收侧 ----
    private int expectedRxSeq;
    /** 最新 expectedRxSeq 尚未成功提交；多个 DATA 自然合并。 */
    private boolean ackNewDataPending;
    /** 已有一次 ACK 提交待执行；本地写被拒时保持。 */
    private boolean ackSubmitPending;
    private int ackRepeatsRemaining;
    private long lastAckSentAtMs;
    private byte[] reasmBuf;
    private int reasmLen;
    private boolean reasmActive;

    // ---- 本地写拒收诊断 ----
    /** 连续被拒次数；任一次提交成功即清零。 */
    private int consecutiveWriteRejects;
    private int lastRejectedFrameLen;
    private int tickCounter;
    /** 首次连续被拒的时刻；提交成功即清零。 */
    private long rejectSinceMs;
    /** 连续被拒超过该时长即判定为帧长超限并拆链。 */
    private static final long WRITE_REJECT_FATAL_MS = 2000L;

    // ---- 握手 ----
    private int linkSetupTries;
    private ScheduledFuture<?> linkSetupTask;
    private ScheduledFuture<?> tickTask;

    // ---- 非致命错误限流 ----
    private static final long ERROR_THROTTLE_MS = 1000L;
    private final Object errorThrottleLock = new Object();
    private TransportError lastErrorKind;
    private long lastErrorLogAtMs;
    private long suppressedErrorCount;

    public interface Logger {
        void log(String message);
    }

    public DefaultReliableTransport(FrameLink link,
                                    TransportConfig config,
                                    boolean initiator,
                                    Logger logger) {
        config.validate();
        this.link = link;
        this.config = config;
        this.initiator = initiator;
        this.logger = logger != null ? logger : m -> { };
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transport-worker");
            t.setDaemon(true);
            return t;
        });
        link.setCallback(this);
    }

    // ==================== 公开接口 ====================

    /**
     * 向 worker 投递任务，并容忍「worker 已关闭」。
     *
     * <p><b>必要性</b>：FrameLink 回调由 BLE 回调线程触发，而 {@link #shutdown()}
     * 由 UI 线程调用，二者天然存在时间差 —— 传输层关闭之后，底层链路仍可能
     * 投递若干迟到回调（断开上报、最后几帧 Notify、写完成）。
     * 若不容忍拒绝，{@link RejectedExecutionException} 会抛在 BLE 回调线程上；
     * 那是一条没有默认异常处理器的 HandlerThread，异常等于**进程崩溃**。
     *
     * <p>需要在拒绝时做补偿动作的调用点（如 {@code send()} 要归还名额）
     * 不用本方法，自行捕获。
     */
    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // worker 已关闭：传输层已被上层废弃，迟到回调无需处理
        }
    }

    @Override
    public void start() {
        submit(() -> {
            resetAll();
            setState(LinkState.IDLE);
            reasmBuf = new byte[config.rxMaxMessageSize];
            if (tickTask == null) {
                tickTask = worker.scheduleWithFixedDelay(
                        this::onTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
            }
        });
    }

    @Override
    public void stop() {
        submit(() -> fatal(TransportFatalError.CLOSED_BY_LOCAL, "本端主动停止"));
    }

    @Override
    public void send(byte[] message) {
        if (message == null || message.length == 0) {
            return;
        }
        if (stateRef.get() != LinkState.READY) {
            notifyError(TransportError.NOT_READY, "链路未就绪，丢弃提交的消息");
            return;
        }
        if (message.length > config.txMaxMessageSize) {
            notifyError(TransportError.MESSAGE_TOO_LARGE_TX,
                    "消息 " + message.length + " B 超过上限 " + config.txMaxMessageSize + " B");
            return;
        }

        // 准入判定与计数必须在【调用线程】同步完成，使 isReadyToSend() 立即反映本次提交。
        int now = outstanding.incrementAndGet();
        if (now > config.txQueueMaxMessages) {
            outstanding.decrementAndGet();
            postFatal(TransportFatalError.TX_QUEUE_OVERFLOW,
                    "TX 队列溢出（容量 " + config.txQueueMaxMessages
                            + "）：数据源未遵守 isReadyToSend()");
            return;
        }

        try {
            worker.execute(() -> {
                if (state != LinkState.READY) {
                    releaseSlot();
                    notifyError(TransportError.NOT_READY, "链路未就绪，丢弃提交的消息");
                    return;
                }
                txQueue.addLast(message);
                pump();
            });
        } catch (RejectedExecutionException e) {
            releaseSlot();   // worker 已关闭
        }
    }

    /**
     * 释放一个提交名额，且绝不使计数变负。
     *
     * <p>必要性：{@code clearBuffers()} 会把计数归零，而此刻可能有其他线程
     * 刚在 {@code send()} 中递增、其 Runnable 稍后才执行并走到释放分支。
     * 若直接 decrement 会出现负值，使 {@code isReadyToSend()} 永久为 true，
     * 反过来又诱发 TX_QUEUE_OVERFLOW。
     */
    private void releaseSlot() {
        while (true) {
            int cur = outstanding.get();
            if (cur <= 0) {
                needNotify = true;
                return;
            }
            if (outstanding.compareAndSet(cur, cur - 1)) {
                needNotify = true;   // 腾出名额 → 需要通知数据源续产
                return;
            }
        }
    }

    /**
     * 就绪阈值取 {@code txQueueMaxMessages / 2}，而非硬上限。
     *
     * <p>留出的余量专门给<b>控制类报文</b>：它们直接 send() 且不查询就绪信号，
     * 必须保证不会因数据源占满队列而触发 TX_QUEUE_OVERFLOW。
     * 这也正好使数据源自然维持「未完成消息数 ≤ 2」。
     */
    private int readyThreshold() {
        return Math.max(1, config.txQueueMaxMessages / 2);
    }

    @Override
    public boolean isReadyToSend() {
        return stateRef.get() == LinkState.READY
                && !sendStalledRef.get()
                && outstanding.get() < readyThreshold();
    }

    @Override
    public boolean isSendStalled() {
        return stateRef.get() == LinkState.READY && sendStalledRef.get();
    }

    @Override
    public boolean isReady() {
        return stateRef.get() == LinkState.READY;
    }

    @Override
    public int getMaxMessageSize() {
        return config.txMaxMessageSize;
    }

    @Override
    public boolean isStreamingCapable() {
        return frozenAttPayload >= config.minAttPayloadForStreaming;
    }

    @Override
    public TransportMetrics getMetrics() {
        if (state == LinkState.READY && linkUpAtMs > 0) {
            metrics.linkUpTimeMs = System.currentTimeMillis() - linkUpAtMs;
        }
        return metrics;
    }

    @Override
    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    // ==================== FrameLink 回调 ====================

    @Override
    public void onFrameLinkReady() {
        submit(() -> {
            if (state == LinkState.DOWN) {
                return;
            }
            if (initiator) {
                beginLinkSetup();
            } else {
                // 响应方等待对端的 LINK_SETUP。
                setState(LinkState.SETUP_WAIT);
                logger.log("底层链路就绪，等待对端 LINK_SETUP");
            }
        });
    }

    @Override
    public void onFrameReceived(byte[] frame) {
        submit(() -> handleFrame(frame));
    }

    @Override
    public void onFrameWriteComplete() {
        submit(this::pump);
    }

    @Override
    public void onFrameLinkDown(String reason) {
        submit(() -> fatal(TransportFatalError.GATT_DISCONNECTED,
                reason == null ? "底层链路断开" : reason));
    }

    // ==================== 握手 ====================

    private void beginLinkSetup() {
        resetSessionState();
        freezeAttPayload();
        setState(LinkState.SETUP_WAIT);
        linkSetupTries = 0;
        sendLinkSetupOnce();
    }

    private void sendLinkSetupOnce() {
        if (state != LinkState.SETUP_WAIT) {
            return;
        }
        linkSetupTries++;
        if (linkSetupTries > config.linkSetupMaxRetry) {
            fatal(TransportFatalError.LINK_SETUP_FAILED,
                    "LINK_SETUP 重发 " + config.linkSetupMaxRetry + " 次仍未收到 ACK");
            return;
        }
        boolean submitted =
                link.writeFrame(FrameCodec.encodeLinkSetup(false, config.protocolVersion));
        // 必须区分「已发出」与「本地提交失败」：两者的排查方向完全不同
        // （对端不应答 vs 本端根本没发出去）。
        logger.log("发送 LINK_SETUP(version=" + config.protocolVersion
                + ")，第 " + linkSetupTries + " 次"
                + (submitted ? "" : "（本地提交失败，未发出）"));
        cancelLinkSetupTask();
        linkSetupTask = worker.schedule(this::sendLinkSetupOnce,
                config.linkSetupTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelLinkSetupTask() {
        if (linkSetupTask != null) {
            linkSetupTask.cancel(false);
            linkSetupTask = null;
        }
    }

    private void enterReady() {
        cancelLinkSetupTask();
        setState(LinkState.READY);
        linkUpAtMs = System.currentTimeMillis();
        stallSinceMs = 0L;
        peerCredit = config.peerRxWindow;
        lastReadyToSend = true;
        logger.log("链路 READY：attPayload=" + frozenAttPayload
                + "，发送窗口=" + peerCredit + " 帧，单条消息上限=" + config.txMaxMessageSize + " B");
        if (callback != null) {
            callback.onReady(config.txMaxMessageSize, isStreamingCapable());
        }
        pump();
    }

    /**
     * 冻结 attPayload。握手完成后必须忽略 MTU 变更，
     * 以保证一条消息在发送期间分片逻辑一致。
     */
    private void freezeAttPayload() {
        int att = link.getAttPayloadSize();
        int negotiated = att > 0 ? att : FrameCodec.attPayloadOf(config.fallbackMtu);
        frozenAttPayload = Math.min(negotiated, MAX_EFFECTIVE_ATT_PAYLOAD);
    }

    // ==================== 接收 ====================

    private void handleFrame(byte[] raw) {
        if (state == LinkState.DOWN) {
            return;
        }
        FrameCodec.Frame f = FrameCodec.decode(raw);
        if (f.type == null) {
            fatal(TransportFatalError.PROTOCOL_VIOLATION, "解码失败：" + f.error);
            return;
        }

        switch (f.type) {
            case LINK_SETUP:
                if (f.version != config.protocolVersion) {
                    fatal(TransportFatalError.VERSION_MISMATCH,
                            "对端 protocolVersion=" + f.version
                                    + "，本端=" + config.protocolVersion);
                    return;
                }
                // 收到即强制复位本端全部传输状态（会话边界同步）。
                resetSessionState();
                freezeAttPayload();
                link.writeFrame(FrameCodec.encodeLinkSetup(true, config.protocolVersion));
                logger.log("收到 LINK_SETUP，已复位会话状态并回送 LINK_SETUP_ACK");
                enterReady();
                return;

            case LINK_SETUP_ACK:
                if (f.version != config.protocolVersion) {
                    fatal(TransportFatalError.VERSION_MISMATCH,
                            "对端 protocolVersion=" + f.version
                                    + "，本端=" + config.protocolVersion);
                    return;
                }
                if (state == LinkState.SETUP_WAIT) {
                    logger.log("收到 LINK_SETUP_ACK");
                    enterReady();
                }
                return;

            case ACK:
                if (state == LinkState.READY) {
                    onAck(f.ackSeq, f.credit);
                }
                return;

            case DATA:
                if (state != LinkState.READY) {
                    // 握手未完成就收到 DATA：会话边界未对齐。
                    fatal(TransportFatalError.PROTOCOL_VIOLATION,
                            "链路未 READY 时收到 DATA 帧");
                    return;
                }
                onData(f);
                return;

            default:
        }
    }

    private void onData(FrameCodec.Frame f) {
        if (f.seq != expectedRxSeq) {
            if (seqLt(f.seq, expectedRxSeq)) {
                metrics.duplicateFrames++;
                // 本版无任何重传，重复帧理论上不可能出现；出现即暴露。
                fatal(TransportFatalError.UNEXPECTED_DUPLICATE,
                        "收到重复帧 seq=" + f.seq + "，期望 " + expectedRxSeq);
            } else {
                metrics.recordGap(expectedRxSeq, f.seq);   // ★ 实验核心埋点
                fatal(TransportFatalError.FRAME_LOSS,
                        "检测到 seq 缺口：期望 " + expectedRxSeq + "，收到 " + f.seq
                                + "，丢失 " + (((f.seq - expectedRxSeq) & 0xFFFF)) + " 帧");
            }
            return;
        }

        if (f.isSom()) {
            if (reasmActive) {
                fatal(TransportFatalError.PROTOCOL_VIOLATION, "重组进行中又收到 SOM");
                return;
            }
            reasmActive = true;
            reasmLen = 0;
        } else {
            if (!reasmActive) {
                fatal(TransportFatalError.PROTOCOL_VIOLATION, "无进行中的重组却收到非 SOM 帧");
                return;
            }
        }

        if (reasmLen + f.payload.length > config.rxMaxMessageSize) {
            fatal(TransportFatalError.MESSAGE_TOO_LARGE_RX,
                    "重组长度超过 " + config.rxMaxMessageSize + " B");
            return;
        }
        System.arraycopy(f.payload, 0, reasmBuf, reasmLen, f.payload.length);
        reasmLen += f.payload.length;

        expectedRxSeq = (expectedRxSeq + 1) & 0xFFFF;
        ackNewDataPending = true;
        ackSubmitPending = true;
        metrics.totalDataFramesRx++;
        metrics.totalBytesRx += f.payload.length;

        if (f.isEom()) {
            int actual = Crc16.of(reasmBuf, 0, reasmLen);
            if (actual != f.msgCrc16) {
                fatal(TransportFatalError.CRC_MISMATCH,
                        "整消息 CRC16 不匹配：计算 0x" + Integer.toHexString(actual)
                                + "，收到 0x" + Integer.toHexString(f.msgCrc16));
                return;
            }
            byte[] msg = new byte[reasmLen];
            System.arraycopy(reasmBuf, 0, msg, 0, reasmLen);
            reasmActive = false;
            reasmLen = 0;
            if (callback != null) {
                callback.onMessageReceived(msg);   // 严格 FIFO，且仅一次
            }
        }

        // 每处理完一帧立即累计确认，删除 delayed ACK 的计时状态，
        // 并让固定窗口尽快推进。
        sendAckIfPending();
    }

    // ==================== ACK ====================

    /**
     * 尝试提交最新累计 ACK。
     *
     * <p>新 DATA 的立即 ACK 成功后重置 3 次快速补发额度；额度耗尽后改为
     * 低频保活。补发成功才消耗快速额度；本地写闸门拒绝不消耗额度，
     * 由写完成回调或 tick 在 DATA 之前优先重试。ACK 携带最新
     * {@code expectedRxSeq}，因此在重试期间到达的多个 DATA 仍只需一份状态。
     *
     * <p>credit 固定为 rxWindow。若未来改为异步持有接收槽位，必须把设置
     * {@code ackNewDataPending} 的时机延后到槽位释放之后。
     */
    private void sendAckIfPending() {
        if (!ackSubmitPending) {
            return;
        }
        if (link.writeFrame(FrameCodec.encodeAck(expectedRxSeq, config.rxWindow))) {
            boolean repeat = !ackNewDataPending;
            ackSubmitPending = false;
            lastAckSentAtMs = System.currentTimeMillis();
            if (repeat) {
                if (ackRepeatsRemaining > 0) {
                    ackRepeatsRemaining--;
                } else {
                    metrics.ackKeepAliveSent++;
                }
                metrics.periodicAckSent++;
            } else {
                ackNewDataPending = false;
                ackRepeatsRemaining = ACK_REPEAT_LIMIT;
            }
        }
    }

    /** 先按快速间隔补发 3 次，之后按低频间隔持续保活最新累计 ACK。 */
    private void scheduleAckRepeatIfDue() {
        if (ackSubmitPending || lastAckSentAtMs == 0L) {
            return;
        }
        int intervalMs = ackRepeatsRemaining > 0
                ? config.ackRepeatMs : config.ackKeepAliveMs;
        if (System.currentTimeMillis() - lastAckSentAtMs >= intervalMs) {
            ackSubmitPending = true;
        }
    }

    private void onAck(int ackSeq, int credit) {
        int inFlight = seqDiff(nextTxSeq, lastAckSeq);
        int advance = seqDiff(ackSeq, lastAckSeq);
        if (advance > inFlight) {
            return;   // 过期/越界，忽略
        }
        if (advance > 0) {
            lastAckSeq = ackSeq;
            recoverFromAckStall(System.currentTimeMillis());
            // 只在 ackSeq 真正推进时重置 stall 计时起点。
            // 过期或重复 ACK 不代表窗口取得进展，不能伪造软停滞恢复。
            // 置 0 而非置 now：窗口是否仍非空由 checkAckStall 判定后重新起算。
            stallSinceMs = 0L;
        }
        peerCredit = credit;
        pump();
    }

    // ==================== 发送 ====================

    private void pump() {
        pumpFrames();
        // 一条消息发完 / 队列被取空都会改变就绪状态，统一在此触发边沿，
        // 避免依赖 20ms tick 才能通知数据源续产。
        notifyReadyToSendIfNeeded();
    }

    private void pumpFrames() {
        if (state != LinkState.READY) {
            return;
        }
        while (true) {
            if (!link.isLinkConnected()) {
                return;
            }

            // ACK 优先于 DATA 争用写通道。
            //
            // 二者共用同一条 BLE 写通道与同一个「单帧在途」闸门。若 DATA 无条件抢占，
            // 双向同时传输时接收侧的 ACK 会被持续拒收，对端窗口迟迟得不到推进，
            // 最终被误判为 ACK_STALL —— 本质是一种活锁。
            // 让出闸门的代价极小（ACK 仅 4 字节），换来的是窗口始终能推进。
            if (ackSubmitPending) {
                sendAckIfPending();
                if (ackSubmitPending) {
                    return;   // 闸门仍不可用，等写完成回调
                }
            }

            // ACK 软停滞只暂停 DATA；反向 DATA 的累计 ACK 仍必须能够发送。
            if (sendStalled) {
                return;
            }

            if (pendingFrame != null) {
                if (!link.canAcceptWrite()) {
                    return;
                }
                if (!link.writeFrame(pendingFrame)) {
                    onWriteRejected(pendingFrame.length);
                    return;   // 仍被拒，等下一次 writeComplete 或 tick
                }
                consecutiveWriteRejects = 0;
                rejectSinceMs = 0L;
                metrics.totalBytesTx += pendingFrame.length;
                pendingFrame = null;
                continue;
            }

            if (seqDiff(nextTxSeq, lastAckSeq) >= peerCredit) {
                if (windowBlockedSinceMs == 0) {
                    windowBlockedSinceMs = System.currentTimeMillis();
                }
                return;   // 窗口占满，等 ACK
            }
            if (windowBlockedSinceMs != 0) {
                metrics.windowBlockedMs += System.currentTimeMillis() - windowBlockedSinceMs;
                windowBlockedSinceMs = 0;
            }
            if (!link.canAcceptWrite()) {
                return;
            }

            if (curMsg == null) {
                byte[] next = txQueue.pollFirst();
                if (next == null) {
                    return;   // 无待发消息
                }
                curMsg = next;
                curOff = 0;
                curMsgCrc = Crc16.of(next);
                // 无需在此触发就绪边沿：outstandingMessages() 已把 curMsg 计入，
                // 出队并不改变未完成消息数。
            }

            int remaining = curMsg.length - curOff;
            int n = FrameCodec.nextChunkSize(remaining, frozenAttPayload);
            if (n <= 0) {
                fatal(TransportFatalError.LOCAL_WRITE_FAILED,
                        "attPayload=" + frozenAttPayload + " 过小，无法承载任何载荷");
                return;
            }
            boolean som = curOff == 0;
            boolean eom = (curOff + n) >= curMsg.length;
            int flags = (som ? DataFlags.SOM : 0) | (eom ? DataFlags.EOM : 0);

            byte[] frame = FrameCodec.encodeData(
                    nextTxSeq, flags, curMsg, curOff, n, eom ? curMsgCrc : 0);
            nextTxSeq = (nextTxSeq + 1) & 0xFFFF;
            curOff += n;
            if (eom) {
                curMsg = null;
                curOff = 0;
                // 整条消息的全部帧已编码并提交，释放一个名额。
                releaseSlot();
            }

            if (!link.writeFrame(frame)) {
                pendingFrame = frame;   // seq 已分配，保持不变，稍后原样重试
                onWriteRejected(frame.length);
                return;
            }
            consecutiveWriteRejects = 0;
            rejectSinceMs = 0L;
            metrics.totalBytesTx += frame.length;
        }
    }

    /** 本地写被拒：保留原帧，等待写完成事件或退避 tick 原样重试。 */
    private void onWriteRejected(int frameLen) {
        consecutiveWriteRejects++;
        lastRejectedFrameLen = frameLen;
        long now = System.currentTimeMillis();
        if (rejectSinceMs == 0L) {
            rejectSinceMs = now;
        }
    }

    /**
     * 通知数据源「可以继续生产了」。
     *
     * <p><b>不能依赖纯边沿检测</b>。原因：
     * 数据源在自己的线程上调用 {@code send()} 把 {@code outstanding} 顶到阈值
     * （信号转 false），但该跳变只存在于调用线程；worker 线程执行到
     * 本方法时 {@code outstanding} 往往已经回落，于是 worker 从未观察到
     * 「false」这一侧，之后也就永远产生不了「false → true」的上升沿，
     * 数据源被永久挂起。真机表现为传输在第一个数据块后卡死（进度停在 4 KB）。
     *
     * <p>因此改为<b>粘性通知</b>：只要释放过名额（{@link #needNotify}）
     * 且当前可发送，就通知一次。标志位在成功通知后才清除，
     * 故通知不会因任何时序错位而丢失。
     */
    private void notifyReadyToSendIfNeeded() {
        if (inReadyCallback) {
            return;   // 防止回调内 send() 递归重入
        }
        boolean now = isReadyToSend();
        boolean shouldNotify = now && (needNotify || !lastReadyToSend);
        lastReadyToSend = now;
        if (!shouldNotify) {
            return;
        }
        Callback cb = callback;
        if (cb == null) {
            return;
        }
        needNotify = false;
        inReadyCallback = true;
        try {
            cb.onReadyToSend();
        } finally {
            inReadyCallback = false;
            lastReadyToSend = isReadyToSend();
        }
    }

    // ==================== 定时 tick ====================

    private void onTick() {
        try {
            if (state == LinkState.READY) {
                // 新 DATA 立即确认；先快速补发 3 次，随后低频持续保活。
                scheduleAckRepeatIfDue();
                sendAckIfPending();
                checkAckStall();
                // 本地写持续被拒时退避重试：以 5ms tick 全速重试只会空转烧 CPU，
                // 协议栈缓冲需要时间腾出。每第 4 个 tick（约 20ms）试一次即可。
                if (consecutiveWriteRejects == 0 || (++tickCounter & 0x3) == 0) {
                    pump();
                }
            }
        } catch (Throwable t) {
            fatal(TransportFatalError.PROTOCOL_VIOLATION, "内部异常：" + t);
        }
    }

    private void checkAckStall() {
        // 本地写持续被拒时，问题在【本端提交】而非【对端确认】。
        // 若仍按 ACK_STALL 上报，会把「帧根本没发出去」误诊为「对端没回 ACK」，
        // 使排查方向完全走偏。因此优先判定并单独上报。
        if (rejectSinceMs != 0L
                && (System.currentTimeMillis() - rejectSinceMs) >= WRITE_REJECT_FATAL_MS) {
            fatal(TransportFatalError.LOCAL_WRITE_FAILED,
                    "本地写连续被拒 " + consecutiveWriteRejects + " 次"
                            + "（帧长 " + lastRejectedFrameLen + " B，attPayload "
                            + frozenAttPayload + " B）"
                            + "：极可能是该帧长超出本机 BLE 协议栈的 Notify/Write 承载能力，"
                            + "请降低 MTU 后重试");
            return;
        }

        int inFlight = seqDiff(nextTxSeq, lastAckSeq);
        boolean hasTxWorkWaiting = pendingFrame != null || curMsg != null || !txQueue.isEmpty();
        if (inFlight < peerCredit || !hasTxWorkWaiting) {
            // ACK_STALL 只度量「窗口已满且确实还有 DATA 等待发送」。
            // 最后一个 ACK 丢失但当前无后续数据时无需拆链；未来的新 DATA 仍可发送，
            // 其立即累计 ACK 会自然追平旧帧。
            stallSinceMs = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (stallSinceMs == 0L) {
            // 窗口刚进入「满且仍有数据等待」状态，此刻才开始计时。
            //
            // 这一点至关重要：若沿用「距上次 ACK 推进的时长」，则链路就绪后
            // 空闲一段时间再发首帧时，计时器早已跑过阈值，首帧一发出就会被
            // 误判为停滞（真机上表现为「发送窗口 1739ms 无推进（未确认 1 帧）」）。
            stallSinceMs = now;
            return;
        }
        long idle = now - stallSinceMs;
        if (idle >= config.ackStallTimeoutMs && !sendStalled) {
            enterAckStall(idle, inFlight);
        }
    }

    /**
     * ACK_STALL 是可恢复的发送暂停，不是会话边界。
     *
     * <p>窗口与全部 TX/RX 状态原样保留；接收方向仍可工作。低频累计 ACK
     * 一旦重新到达，{@link #onAck(int, int)} 会恢复发送并自然冲刷原队列。
     */
    private void enterAckStall(long idleMs, int inFlight) {
        sendStalled = true;
        sendStalledRef.set(true);
        lastReadyToSend = false;
        metrics.ackStallEvents++;
        logger.log("发送软停滞：窗口 " + idleMs + "ms 无推进（未确认 "
                + inFlight + " 帧），保留队列并等待链路恢复");
        Callback cb = callback;
        if (cb != null) {
            cb.onSendStalled(idleMs);
        }
    }

    /** 只有累计 ACK 真正推进才恢复，重复旧 ACK 不得解除软停滞。 */
    private void recoverFromAckStall(long nowMs) {
        if (!sendStalled) {
            return;
        }
        long stalledForMs = stallSinceMs == 0L ? 0L : Math.max(0L, nowMs - stallSinceMs);
        sendStalled = false;
        sendStalledRef.set(false);
        needNotify = true;
        metrics.ackStallRecoveries++;
        if (stalledForMs > metrics.maxAckStallMs) {
            metrics.maxAckStallMs = stalledForMs;
        }
        logger.log("发送已恢复：累计 ACK 再次推进，停滞 " + stalledForMs + "ms");
        Callback cb = callback;
        if (cb != null) {
            cb.onSendResumed(stalledForMs);
        }
    }

    // ==================== 拆链 ====================

    /** 状态变更唯一入口，保证 worker 权威状态与跨线程镜像一致。 */
    private void setState(LinkState next) {
        state = next;
        stateRef.set(next);
    }

    /** 供非 worker 线程触发拆链。 */
    private void postFatal(TransportFatalError reason, String message) {
        try {
            worker.execute(() -> fatal(reason, message));
        } catch (RejectedExecutionException ignored) {
            // worker 已关闭
        }
    }

    private void fatal(TransportFatalError reason, String message) {
        if (state == LinkState.DOWN) {
            return;   // 幂等
        }
        setState(LinkState.DOWN);
        sendStalled = false;
        sendStalledRef.set(false);
        if (linkUpAtMs > 0) {
            metrics.linkUpTimeMs = System.currentTimeMillis() - linkUpAtMs;
        }
        metrics.recordTeardown(reason);   // 先落盘，重连不会清空
        cancelLinkSetupTask();
        clearBuffers();
        logger.log("链路终止：" + reason + " —— " + message);

        // fatal 是当前逻辑会话的终点，也必须成为物理连接的终点。否则只有本端
        // transport 进入 DOWN，对端 GATT/业务层仍会误以为连接有效。
        // GATT_DISCONNECTED 路径下底层本就已断开，disconnect() 必须幂等。
        try {
            link.disconnect();
        } catch (RuntimeException e) {
            logger.log("主动断开底层连接失败：" + e.getMessage());
        }
        if (callback != null) {
            callback.onLinkDown(reason, message);
        }
    }

    /**
     * 上报非致命错误，并对同类错误做<b>限流</b>。
     *
     * <p>必要性：拆链瞬间可能已有大量 {@code send()} 在途，逐条上报会产生
     * 成千上万次 UI 回调，足以打死主线程 —— 表现为「App 卡死」。
     * 同类错误在 1 秒内只上报一次，其余仅累加计数，
     * 恢复后以汇总形式补报一条。
     */
    private void notifyError(TransportError error, String message) {
        long now = System.currentTimeMillis();
        synchronized (errorThrottleLock) {
            if (error == lastErrorKind && (now - lastErrorLogAtMs) < ERROR_THROTTLE_MS) {
                suppressedErrorCount++;
                return;
            }
            long suppressed = suppressedErrorCount;
            suppressedErrorCount = 0;
            lastErrorKind = error;
            lastErrorLogAtMs = now;
            if (suppressed > 0) {
                message = message + "（另有 " + suppressed + " 条同类错误已合并）";
            }
        }
        logger.log("非致命错误：" + error + " —— " + message);
        Callback cb = callback;
        if (cb != null) {
            cb.onError(error, message);
        }
    }

    private void resetSessionState() {
        nextTxSeq = 0;
        lastAckSeq = 0;
        expectedRxSeq = 0;
        ackNewDataPending = false;
        ackSubmitPending = false;
        ackRepeatsRemaining = 0;
        lastAckSentAtMs = 0L;
        peerCredit = config.peerRxWindow;
        clearBuffers();
        stallSinceMs = 0L;
        sendStalled = false;
        sendStalledRef.set(false);
        windowBlockedSinceMs = 0;
        lastReadyToSend = true;
        needNotify = false;
        inReadyCallback = false;
        consecutiveWriteRejects = 0;
        lastRejectedFrameLen = 0;
        rejectSinceMs = 0L;
    }

    private void clearBuffers() {
        txQueue.clear();
        curMsg = null;
        curOff = 0;
        pendingFrame = null;
        reasmActive = false;
        reasmLen = 0;
        // 队列已清空，名额一并归零，避免重连后就绪信号被旧计数永久压住。
        outstanding.set(0);
    }

    private void resetAll() {
        resetSessionState();
        linkSetupTries = 0;
        linkUpAtMs = 0;
    }

    /** 供重连使用：把传输层恢复到可重新握手的状态。 */
    public void restartForNewConnection() {
        submit(() -> {
            setState(LinkState.IDLE);
            resetAll();
            if (reasmBuf == null) {
                reasmBuf = new byte[config.rxMaxMessageSize];
            }
        });
    }

    public void shutdown() {
        submit(() -> {
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
            cancelLinkSetupTask();
        });
        worker.shutdown();
    }

    // ==================== seq 模比较（uint16 回绕） ====================

    /** 公开以便回环自测直接验证回绕逻辑。 */
    public static int seqDiff(int a, int b) {
        return (a - b) & 0xFFFF;
    }

    public static boolean seqLt(int a, int b) {
        return seqDiff(a, b) > 0x8000;
    }
}
