package com.recorder.client.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.recorder.transport.BleUuids;
import com.recorder.transport.FrameLink;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App 侧 BLE 帧链路实现：一条已建立 GATT 连接上的 Notify 订阅、
 * Write Without Response 与连接状态管理。
 *
 * <p>本类是 {@link FrameLink} 在「App → 设备用 Write Without Response」方向上的实现。
 * 全部 Android BLE 平台差异（权限、GATT busy、回调线程、MTU/PHY 请求）隔离在此。
 *
 * <p>本类只承担「单连接」职责：设备发现由 {@link BleDeviceScanner} 负责；
 * 本类作为 {@link BleDevice} 的连接内部状态被持有，包外不可见。
 *
 * <p><b>发送背压</b>：即使是 Write Without Response 也不得无节制调用写接口，
 * 必须依据 {@code onCharacteristicWrite} 回调逐帧推进，否则会出现 GATT_BUSY
 * 或远端缓冲溢出。无重传版本没有兜底，这是硬要求。
 *
 * <h3>建链阶段的两条硬约束（血泪教训）</h3>
 *
 * <p><b>1. GATT 操作必须串行、且不得在回调线程里同步发下一个操作。</b>
 * {@code connectGatt} 传入本类自有的 {@link #gattHandler}，使全部 GATT 回调
 * 都投递到同一条线程；所有 GATT 调用（发现服务、requestMtu、写 CCCD、
 * 连接优先级、PHY、disconnect/close）也都在该线程排队执行。
 * 曾经的实现在 {@code onConnectionStateChange} 里背靠背发出
 * {@code requestConnectionPriority} + {@code setPreferredPhy} + {@code discoverServices}：
 * 前两者触发链路层过程（连接参数更新、PHY 更新），此时紧跟的 ATT 服务发现
 * 会被本地协议栈静默丢弃，{@code onServicesDiscovered} 永不回调 ——
 * 现象就是 App 永久停在「已建立 GATT 连接，正在发现服务」，
 * 而设备侧停在「等待启用 Notify」。因此性能类请求一律推迟到 CCCD 写成功之后。
 *
 * <p><b>2. 建链每一步都必须有超时。</b> BLE 回调「不必达」是常态：
 * 服务发现、MTU 协商、CCCD 写入任意一步丢回调，若无看门狗就是永久卡死。
 * 数据面早已有写闸门看门狗，控制面同样不能裸奔。
 */
final class ClientFrameLink implements FrameLink {

    /**
     * 首选 MTU。
     *
     * <p><b>为何不是 517</b>：实测某些机型的 GATT Server 在 Notify 帧长
     * 超过约 250 字节时会持续本地拒收（旧版布尔 API 不返回错误码，
     * 表现为 notifyCharacteristicChanged 恒返回 false），导致发送完全停滞。
     * 247 是已验证稳定的取值（attPayload=244）。
     *
     * <p>提升吞吐应优先依赖 2M PHY 与窗口，而非无限抬高 MTU。
     */
    private static final int PREFERRED_MTU = 247;

    // ==================== 建链阶段时序参数 ====================

    /**
     * GATT 连接建立后到发起服务发现之间的静置时间。
     *
     * <p>连接刚建立时协议栈仍在收尾（连接参数、加密、扫描器停止的余波），
     * 立刻发起服务发现容易被丢弃。250ms 对用户不可感知，却显著提升成功率。
     */
    private static final long DISCOVER_DELAY_MS = 250L;

    private static final long CONNECT_TIMEOUT_MS = 10_000L;
    private static final long DISCOVER_TIMEOUT_MS = 8_000L;
    private static final long MTU_TIMEOUT_MS = 3_000L;
    private static final long CCCD_TIMEOUT_MS = 3_000L;

    /**
     * {@code disconnect()} 之后等待 {@code STATE_DISCONNECTED} 的时间，超时才 close。
     *
     * <p>{@code close()} 会立刻注销回调并释放客户端实例，紧跟在 {@code disconnect()}
     * 之后调用会让对端直到链路监督超时（数秒）才发现断开，本机也可能残留 ACL，
     * 从而污染下一次连接 —— 表现为「卡住 → 重连 → 更容易卡住」。
     */
    private static final long DISCONNECT_WAIT_MS = 600L;

    /** 一次建链失败到下一次尝试之间的额外间隔（在 close 完成之后计时）。 */
    private static final long RETRY_DELAY_MS = 600L;

    /**
     * CCCD 写成功后延迟施加性能类请求（高连接优先级 + 2M PHY）的时间。
     *
     * <p>推迟的目的有二：不与服务发现/MTU 协商争抢，也不与传输层
     * 立刻发出的 LINK_SETUP 首帧撞在同一个连接事件上。
     */
    private static final long PERF_TUNING_DELAY_MS = 800L;

    /** 建链总尝试次数（含首次）。超出即上报断开，由用户决定是否再扫描。 */
    private static final int MAX_SETUP_ATTEMPTS = 3;

    /** 建链阶段状态。只在 GATT 线程写入。 */
    private enum Stage {
        IDLE, CONNECTING, CONNECTED, DISCOVERING, MTU, CCCD, READY, RELEASING, DEAD
    }

    interface Events {
        void onLog(String message);

        void onConnected();

        void onMtuChanged(int mtu);

        void onDisconnected(String reason);
    }

    private final Context context;
    private final Events events;

    // ---- GATT 操作串行线程 ----
    private final Object lifecycleLock = new Object();
    private HandlerThread gattThread;
    private Handler gattHandler;

    private volatile BluetoothGatt gatt;
    /** notify（设备 → App）。在GATT 线程写、在传输层线程读，必须 volatile。 */
    private volatile BluetoothGattCharacteristic txChar;
    /** write（App → 设备）。在 GATT 线程写、在传输层线程读，必须 volatile。 */
    private volatile BluetoothGattCharacteristic rxChar;

    private Callback callback;

    private volatile boolean notifyReady;
    private volatile int currentMtu = 23;
    /**
     * 写发送闸门：非 0 表示上一帧提交的时间戳，尚未收到 onCharacteristicWrite。
     *
     * <p>本层不排队：闸门未开时 writeFrame 直接返回 false，
     * 由传输层的 pendingFrame + tick 重试，避免两级队列造成搁死与乱序。
     */
    private volatile long writeInFlightSinceMs;

    /** 写完成回调看门狗：超时即放开闸门，使链路不依赖回调必达。 */
    private static final long WRITE_WATCHDOG_MS = 400L;

    private long lastFailLogMs;

    // ---- 以下字段只在 GATT 线程访问 ----

    private volatile Stage stage = Stage.IDLE;

    /**
     * 建链尝试代号。自增即让全部在途延时任务（超时、重试、延迟发现）
     * 与迟到回调整体失效，无需逐个 removeCallbacks。
     */
    private volatile long attemptSeq;

    private int attempt;
    private BluetoothDevice target;

    /** 已 disconnect、等待 {@code STATE_DISCONNECTED} 后再 close 的实例。 */
    private volatile BluetoothGatt pendingRelease;

    /** true = 链路已进入终止流程，close 完成后释放 GATT 线程，不再重试。 */
    private boolean releasing;
    private boolean terminated;

    /** 断开只上报一次，避免 UI 与传输层收到重复的下降沿。 */
    private final AtomicBoolean downReported = new AtomicBoolean();

    ClientFrameLink(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
    }

    // ==================== GATT 线程 ====================

    private Handler ensureHandler() {
        synchronized (lifecycleLock) {
            if (gattHandler == null) {
                gattThread = new HandlerThread("ble-gatt-client");
                gattThread.start();
                gattHandler = new Handler(gattThread.getLooper());
            }
            return gattHandler;
        }
    }

    /** 已释放时返回 null；调用方必须容忍 null（等价于「链路已终止」）。 */
    private Handler handler() {
        synchronized (lifecycleLock) {
            return gattHandler;
        }
    }

    private void releaseHandler() {
        HandlerThread t;
        synchronized (lifecycleLock) {
            t = gattThread;
            gattThread = null;
            gattHandler = null;
        }
        if (t != null) {
            t.quitSafely();
        }
    }

    /**
     * 投递一个带守卫的延时任务：尝试代号或阶段已变化即自动失效。
     * 这是本类唯一的延时机制，避免维护一堆 Runnable 句柄。
     */
    private void postGuarded(long seq, Stage expect, long delayMs, Runnable action) {
        Handler h = handler();
        if (h == null) {
            return;
        }
        h.postDelayed(() -> {
            if (seq != attemptSeq || stage != expect) {
                return;   // 已进入下一阶段或下一次尝试，任务过期
            }
            action.run();
        }, delayMs);
    }

    /** 为当前阶段挂一个超时看门狗。同一阶段每次尝试只会进入一次，故不会重复挂。 */
    private void armTimeout(long seq, Stage expect, long ms, String what) {
        postGuarded(seq, expect, ms, () ->
                failAttempt(what + "超时（>" + ms + "ms未回调）"));
    }

    // ==================== 连接生命周期 ====================

    void connect(BluetoothDevice device) {
        Handler h = ensureHandler();
        h.post(() -> {
            if (releasing || terminated) {
                return;
            }
            target = device;
            attempt = 0;
            downReported.set(false);
            startAttempt();
        });
    }

    @SuppressLint("MissingPermission")
    private void startAttempt() {
        Handler h = handler();
        if (h == null || target == null) {
            return;
        }
        attemptSeq++;
        attempt++;
        currentMtu = 23;
        notifyReady = false;
        writeInFlightSinceMs = 0L;
        txChar = null;
        rxChar = null;
        stage = Stage.CONNECTING;
        final long seq = attemptSeq;

        events.onLog("正在连接 " + target.getAddress()
                + "（第 " + attempt + "/" + MAX_SETUP_ATTEMPTS + " 次尝试）");

        BluetoothGatt g;
        try {
            // 关键：传入本类的 GATT 线程 Handler，使全部回调在该线程串行投递，
            // 从根上消除「回调先于 gatt 字段赋值到达」的竞态。
            // 初始 PHY 固定 1M：2M 请求推迟到建链完成之后，避免与服务发现相撞。
            g = target.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h);
        } catch (Exception e) {
            g = null;
        }
        if (g == null) {
            failAttempt("connectGatt 提交失败");
            return;
        }
        gatt = g;
        armTimeout(seq, Stage.CONNECTING, CONNECT_TIMEOUT_MS, "建立 GATT 连接");
    }

    /**
     * 一次建链尝试失败：清引用 → 优雅断开 → 重试或终止。
     * 只允许在建链阶段调用；READY 之后的断开走 {@code onConnectionStateChange}。
     */
    private void failAttempt(String reason) {
        failAttempt(reason, detachGatt());
    }

    private void failAttempt(String reason, BluetoothGatt failed) {
        if (releasing || terminated) {
            closeGattInstance(failed);
            return;
        }
        events.onLog("建链失败：" + reason);
        stage = Stage.IDLE;
        beginGracefulClose(failed);

        if (attempt < MAX_SETUP_ATTEMPTS) {
            long delay = DISCONNECT_WAIT_MS + RETRY_DELAY_MS;
            events.onLog("将在 " + delay + "ms 后重试建链");
            final long seq = attemptSeq;
            postGuarded(seq, Stage.IDLE, delay, this::startAttempt);
        } else {
            // 放弃：必须上报，否则 UI 永远停在「正在连接」且无法再次发起连接。
            // 不通知传输层：此刻它仍在 IDLE（从未 READY），且上层会在
            // onDisconnected 里整体 teardown，回推下降沿只会制造关闭竞态。
            releasing = true;
            reportDown("建链失败（已尝试 " + attempt + " 次）：" + reason, false);
            if (pendingRelease == null) {
                finishTerminal();
            }
        }
    }

    /**
     * 摘除当前连接引用并让在途任务失效，返回被摘除的 GATT 实例。
     * 摘除后{@link #isLinkConnected()} 立即为 false，传输层不会再提交写。
     */
    private BluetoothGatt detachGatt() {
        attemptSeq++;
        BluetoothGatt g = gatt;
        gatt = null;
        txChar = null;
        rxChar = null;
        notifyReady = false;
        writeInFlightSinceMs = 0L;
        return g;
    }

    /**
     * 优雅断开：先 {@code disconnect()}，等 {@code STATE_DISCONNECTED} 或超时后才 {@code close()}。
     */
    @SuppressLint("MissingPermission")
    private void beginGracefulClose(BluetoothGatt g) {
        if (g == null) {
            return;
        }
        // 同时只跟踪一个待关闭实例。注意这里不能走 closePendingRelease()：
        // 它带有「终止流程收尾」的副作用，会在 pendingRelease 赋值前就释放
        // GATT 线程，导致优雅等待被跳过。
        BluetoothGatt stale = pendingRelease;
        pendingRelease = g;
        closeGattInstance(stale);
        try {
            g.disconnect();
        } catch (Exception ignored) {
            // 忽略
        }
        Handler h = handler();
        if (h == null) {
            closePendingRelease();
            return;
        }
        h.postDelayed(this::closePendingRelease, DISCONNECT_WAIT_MS);
    }

    /** 幂等：关闭待释放实例；若已进入终止流程则顺带释放 GATT 线程。 */
    private void closePendingRelease() {
        BluetoothGatt g = pendingRelease;
        pendingRelease = null;
        closeGattInstance(g);
        if (releasing) {
            finishTerminal();
        }
    }

    private void finishTerminal() {
        if (terminated) {
            return;
        }
        terminated = true;
        stage = Stage.DEAD;
        attemptSeq++;
        releaseHandler();   // 必须是最后一步：quitSafely 会丢弃后续延时任务
    }

    private void reportDown(String reason, boolean notifyTransport) {
        if (!downReported.compareAndSet(false, true)) {
            return;
        }
        if (notifyTransport) {
            Callback cb = callback;
            if (cb != null) {
                dispatch("onFrameLinkDown", () -> cb.onFrameLinkDown(reason));
            }
        }
        dispatch("onDisconnected", () -> events.onDisconnected(reason));
    }

    /**
     * 派发上层回调。
     *
     * <p><b>上层异常绝不允许杀死 GATT 线程</b>：HandlerThread 没有默认异常处理器，
     * 一次未捕获异常就是进程崩溃，而崩溃点往往落在断开/关闭时序上，
     * 现场难以复现。已经踩过一次 —— 传输层 worker 关闭后迟到的断开上报
     * 抛出 RejectedExecutionException，直接闪退。
     */
    private void dispatch(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            events.onLog("上层回调 " + what + " 抛出异常（已忽略）：" + e);
        }
    }

    /**
     * 终止链路。本方法幂等，且被传输层 {@code fatal()} 复用，
     * 因此不能假设调用时连接一定存在。
     */
    @Override
    public void disconnect() {
        Handler h = handler();
        if (h == null) {
            return;   // 已释放
        }
        h.post(() -> {
            if (releasing || terminated) {
                return;
            }
            releasing = true;
            stage = Stage.RELEASING;
            BluetoothGatt g = detachGatt();
            // 不通知传输层：本端主动断开的发起方本就是上层
            // （UI 的 teardown 或传输层自己的 fatal），回推下降沿既多余，
            // 又会在「transport.shutdown() 已执行」时撞上 worker 拒绝任务。
            reportDown("GATT 连接已断开（本端主动断开）", false);
            beginGracefulClose(g);
            if (pendingRelease == null) {
                finishTerminal();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private static void closeGattInstance(BluetoothGatt candidate) {
        if (candidate != null) {
            try {
                candidate.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    private boolean isCurrentGatt(BluetoothGatt candidate) {
        return candidate != null && candidate == gatt;
    }

    int getCurrentMtu() {
        return currentMtu;
    }

    // ==================== FrameLink ====================

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public int getAttPayloadSize() {
        return Math.max(0, currentMtu - 3);
    }

    @Override
    public boolean isLinkConnected() {
        return gatt != null && rxChar != null && notifyReady;
    }

    @Override
    public boolean canAcceptWrite() {
        if (!isLinkConnected()) {
            return false;
        }
        long since = writeInFlightSinceMs;
        if (since == 0L) {
            return true;
        }
        // 看门狗：部分机型在高负载下会漏掉 onCharacteristicWrite。
        // 若不设兜底，闸门将永久关闭，表现为对端 ACK 停滞。
        if (System.currentTimeMillis() - since > WRITE_WATCHDOG_MS) {
            writeInFlightSinceMs = 0L;
            events.onLog("警告：写完成回调超时（>" + WRITE_WATCHDOG_MS + "ms），已放开发送闸门");
            return true;
        }
        return false;
    }

    /**
     * 由传输层线程直接调用，<b>不</b>转投GATT 线程。
     *
     * <p>数据面依赖同步返回值：提交失败必须立刻让传输层原样重试同一帧，
     * 转投异步会丢掉这个语义并引入第二级队列（乱序、搁死）。
     * 建链完成后控制面已无操作在途，与GATT 线程无竞争。
     */
    @Override
    public boolean writeFrame(byte[] frame) {
        if (!isLinkConnected() || !canAcceptWrite()) {
            return false;
        }
        writeInFlightSinceMs = System.currentTimeMillis();
        return doWrite(frame);
    }

    @SuppressLint("MissingPermission")
    private boolean doWrite(byte[] frame) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = rxChar;
        if (g == null || c == null) {
            writeInFlightSinceMs = 0L;
            return false;
        }
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = g.writeCharacteristic(c, frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(frame);
                ok = g.writeCharacteristic(c);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            // 本地拒收（GATT busy）：放开闸门，由传输层原样重试同一帧。
            writeInFlightSinceMs = 0L;
            long now = System.currentTimeMillis();
            if (now - lastFailLogMs > 1000L) {
                lastFailLogMs = now;
                events.onLog("写请求被本地拒收（GATT 忙），将重试");
            }
        }
        return ok;
    }

    // ==================== 建链状态机（全部在 GATT 线程） ====================

    /**
     * 清除本机对该设备的 GATT 服务缓存，强制真正的空中服务发现。
     *
     * <p><b>为何需要</b>：Android 会按设备地址缓存 GATT 属性数据库并在重连时直接复用。
     * 若设备固件在断开后重建 GATT 服务（属性句柄发生位移），App 就会拿着上一次
     * 的旧句柄去读写 —— 而Write Without Response 按规范<b>没有</b>错误响应，
     * 写到不存在/不匹配的句柄会被静默丢弃，表现为「建链一切正常、但对端从不应答」。
     * 每次连接都清一次缓存可以整类排除该故障，代价只是多花一次真实发现的时间。
     *
     * <p>{@code refresh()} 是隐藏 API，只能反射调用；被系统拦截时降级跳过，
     * 不影响建链。
     */
    private boolean refreshServiceCache(BluetoothGatt g) {
        try {
            return Boolean.TRUE.equals(
                    g.getClass().getMethod("refresh").invoke(g));
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void beginDiscovery(BluetoothGatt g) {
        stage = Stage.DISCOVERING;
        final long seq = attemptSeq;
        events.onLog(refreshServiceCache(g)
                ? "已清除本机 GATT 服务缓存"
                : "本机 GATT 服务缓存无法清除（隐藏 API 不可用），将使用缓存句柄");
        boolean submitted;
        try {
            submitted = g.discoverServices();
        } catch (Exception e) {
            submitted = false;
        }
        if (!submitted) {
            // 返回 false 表示请求根本没进协议栈，回调永远不会来。
            failAttempt("discoverServices 提交失败");
            return;
        }
        events.onLog("正在发现服务");
        armTimeout(seq, Stage.DISCOVERING, DISCOVER_TIMEOUT_MS, "服务发现");
    }

    @SuppressLint("MissingPermission")
    private void beginMtuRequest(BluetoothGatt g) {
        stage = Stage.MTU;
        final long seq = attemptSeq;
        boolean submitted;
        try {
            submitted = g.requestMtu(PREFERRED_MTU);
        } catch (Exception e) {
            submitted = false;
        }
        if (!submitted) {
            events.onLog("MTU 请求提交失败，使用当前 MTU=" + currentMtu);
            beginEnableNotify(g);
            return;
        }
        events.onLog("服务与特征已就绪，正在请求 MTU=" + PREFERRED_MTU);
        // MTU 只是性能项：超时不算建链失败，按当前 MTU 继续启用 Notify。
        postGuarded(seq, Stage.MTU, MTU_TIMEOUT_MS, () -> {
            events.onLog("MTU 协商超时，按当前 MTU=" + currentMtu + " 继续");
            beginEnableNotify(g);
        });
    }

    @SuppressLint("MissingPermission")
    private void beginEnableNotify(BluetoothGatt g) {
        stage = Stage.CCCD;
        final long seq = attemptSeq;
        BluetoothGattCharacteristic c = txChar;
        if (c == null) {
            failAttempt("recorder_tx 特征丢失");
            return;
        }
        BluetoothGattDescriptor cccd = c.getDescriptor(BleUuids.CCCD);
        if (cccd == null) {
            failAttempt("recorder_tx 缺少 CCCD 描述符");
            return;
        }
        boolean submitted;
        try {
            if (!g.setCharacteristicNotification(c, true)) {
                failAttempt("setCharacteristicNotification 失败");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                submitted = g.writeDescriptor(cccd,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                submitted = g.writeDescriptor(cccd);
            }
        } catch (Exception e) {
            submitted = false;
        }
        if (!submitted) {
            failAttempt("CCCD 写入提交失败");
            return;
        }
        events.onLog("正在启用 Notify");
        armTimeout(seq, Stage.CCCD, CCCD_TIMEOUT_MS, "CCCD 写入");
    }

    private void onSetupComplete(BluetoothGatt g) {
        stage = Stage.READY;
        notifyReady = true;
        final long seq = attemptSeq;
        events.onConnected();
        events.onLog("Notify 已启用，开始传输层握手");
        Callback cb = callback;
        if (cb != null) {
            dispatch("onFrameLinkReady", cb::onFrameLinkReady);
        }
        // 性能类请求推迟到握手之后：链路层过程（连接参数/PHY 更新）
        // 与 ATT 事务撞在一起是服务发现丢回调的元凶，绝不能放在关键路径上。
        postGuarded(seq, Stage.READY, PERF_TUNING_DELAY_MS, () -> applyPerformanceTuning(g));
    }

    @SuppressLint("MissingPermission")
    private void applyPerformanceTuning(BluetoothGatt g) {
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        } catch (Exception ignored) {
            // 尽力而为，失败不影响链路
        }
        try {
            // 2M PHY：物理层速率翻倍，是提升吞吐最有效的单项措施。
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED);
        } catch (Exception ignored) {
            // 不支持 2M 时自动回落 1M
        }
    }

    // ==================== GATT 回调（均在 GATT 线程） ====================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g == pendingRelease) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closePendingRelease();   // 对端已确认断开，可以安全 close
                }
                return;
            }
            if (!isCurrentGatt(g)) {
                closeGattInstance(g);   // 旧连接的迟到回调，不得误伤当前连接
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // status != 0 的「已连接」是协议栈异常态（如 133），
                    // 继续走服务发现注定拿不到回调，直接重试更快。
                    failAttempt("GATT 连接异常，status=" + status);
                    return;
                }
                stage = Stage.CONNECTED;
                final long seq = attemptSeq;
                events.onLog("已建立 GATT 连接，" + DISCOVER_DELAY_MS + "ms 后发现服务");
                postGuarded(seq, Stage.CONNECTED, DISCOVER_DELAY_MS, () -> beginDiscovery(g));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasReady = stage == Stage.READY;
                BluetoothGatt dead = detachGatt();
                closeGattInstance(dead);   // 对端已确认断开，无需再等待
                if (wasReady) {
                    // 已就绪后掉线：不自动重连，交给用户重新扫描（与既有行为一致）。
                    // 这是唯一必须通知传输层的路径 —— 真实掉线要拆链并终止业务会话。
                    stage = Stage.IDLE;
                    releasing = true;
                    reportDown("GATT 连接已断开（status=" + status + "）", true);
                    finishTerminal();
                } else {
                    // 建链阶段掉线：可重试。此时传输层仍在 IDLE，
                    // 绝不能上报 onFrameLinkDown —— 那会让传输层提前进入 DOWN，
                    // 重试成功后的 onFrameLinkReady 将被直接丢弃。
                    failAttempt("建链阶段连接断开，status=" + status, null);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (!isCurrentGatt(g) || stage != Stage.DISCOVERING) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAttempt("服务发现失败，status=" + status);
                return;
            }
            BluetoothGattService service = g.getService(BleUuids.SERVICE);
            if (service == null) {
                failAttempt("未找到目标 Service " + BleUuids.SERVICE);
                return;
            }
            txChar = service.getCharacteristic(BleUuids.CHAR_TX);
            rxChar = service.getCharacteristic(BleUuids.CHAR_RX);
            if (txChar == null || rxChar == null) {
                failAttempt("未找到 recorder_tx / recorder_rx 特征");
                return;
            }
            logAttributeTable(service, txChar, rxChar);
            // 先协商 MTU，再启用 Notify：
            // 传输层握手时会冻结 attPayload，故 MTU 必须在握手前确定。
            beginMtuRequest(g);
        }

        @Override
        public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            if (!isCurrentGatt(g)) {
                return;
            }
            events.onLog("PHY 已更新：tx=" + phyName(txPhy) + " rx=" + phyName(rxPhy));
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (!isCurrentGatt(g)) {
                return;
            }
            currentMtu = mtu;
            events.onMtuChanged(mtu);
            events.onLog("MTU 已协商为 " + mtu + "（attPayload " + (mtu - 3) + " B）");
            if (stage != Stage.MTU) {
                return;   // 迟到的 MTU 回调（已按超时路径继续），只更新数值
            }
            beginEnableNotify(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (!isCurrentGatt(g) || stage != Stage.CCCD) {
                return;
            }
            if (!BleUuids.CCCD.equals(descriptor.getUuid())) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAttempt("CCCD 写入失败，status=" + status);
                return;
            }
            onSetupComplete(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (!isCurrentGatt(g)) {
                return;
            }
            if (BleUuids.CHAR_TX.equals(characteristic.getUuid())) {
                deliver(value);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            if (!isCurrentGatt(g)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return;   // 新回调已处理，避免重复投递
            }
            if (BleUuids.CHAR_TX.equals(characteristic.getUuid())) {
                deliver(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (!isCurrentGatt(g)) {
                return;
            }
            writeInFlightSinceMs = 0L;   // 放开闸门
            Callback cb = callback;
            if (cb != null) {
                dispatch("onFrameWriteComplete", cb::onFrameWriteComplete);
            }
        }
    };

    /**
     * 打印发现到的属性表（句柄 + 属性位）。
     *
     * <p>句柄是判断「设备重启/重连后是否重建了 GATT 表」的唯一直接证据：
     * 同一台设备两次连接的句柄若不一致，就说明固件动过属性表；
     * 而属性位则用于确认 recorder_rx 真的支持 Write Without Response ——
     * 若不支持，App 发出的 Write Command 会被对端按规范静默丢弃，
     * 现象与「对端不应答」完全一致，极难区分。
     */
    private void logAttributeTable(BluetoothGattService service,
                                   BluetoothGattCharacteristic tx,
                                   BluetoothGattCharacteristic rx) {
        events.onLog("属性表：service handle=" + service.getInstanceId()
                + "，tx handle=" + tx.getInstanceId()
                + " prop=0x" + Integer.toHexString(tx.getProperties())
                + "，rx handle=" + rx.getInstanceId()
                + " prop=0x" + Integer.toHexString(rx.getProperties()));
        if ((tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            events.onLog("警告：recorder_tx 未声明 NOTIFY 属性，对端将无法上行数据");
        }
        if ((rx.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            events.onLog("警告：recorder_rx 未声明 WRITE_NO_RESPONSE 属性，"
                    + "本端的 Write Command 可能被对端静默丢弃");
        }
        if (rx.getDescriptor(BleUuids.CCCD) == null
                && tx.getDescriptor(BleUuids.CCCD) == null) {
            events.onLog("警告：未发现任何 CCCD 描述符");
        }
    }

    private static String phyName(int phy) {        switch (phy) {
            case BluetoothDevice.PHY_LE_1M:
                return "1M";
            case BluetoothDevice.PHY_LE_2M:
                return "2M";
            case BluetoothDevice.PHY_LE_CODED:
                return "Coded";
            default:
                return "未知(" + phy + ")";
        }
    }

    private void deliver(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        Callback cb = callback;
        if (cb != null) {
            // 只做「复制 + 投递」，不在 GATT 回调线程上解码。
            byte[] copy = new byte[value.length];
            System.arraycopy(value, 0, copy, 0, value.length);
            dispatch("onFrameReceived", () -> cb.onFrameReceived(copy));
        }
    }
}
