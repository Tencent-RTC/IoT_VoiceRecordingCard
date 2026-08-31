package com.recorder.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.ble.BleDevice;
import com.recorder.client.ble.BleDeviceScanner;
import com.recorder.client.ble.DiscoveredDevice;
import com.recorder.client.device.DeviceBindingStore;
import com.recorder.transport.DefaultReliableTransport;
import com.recorder.transport.ReliableTransport;
import com.recorder.transport.TransportConfig;
import com.recorder.transport.TransportError;
import com.recorder.transport.TransportFatalError;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 设备连接基建（绑定驱动）。
 *
 * <p>负责「定向扫描已绑定设备 → 建链 → 传输层握手 → 业务会话挂载」的完整生命周期，
 * 页面层只需订阅 {@link State} 变化并渲染。连接以 {@link DeviceBindingStore} 中的
 * 绑定信息为前提：未绑定时不做任何扫描；已绑定后按序列号定向扫描，链断后按固定
 * 节奏静默重连，无需用户操作。
 *
 * <p>进程级单例（{@link #get(Context)}）：绑定页绑定成功、设备信息页解绑均可直接
 * 驱动连接生命周期，无需经过宿主 Activity 中转。页面通过
 * {@link #addListener(Listener)} / {@link #removeListener(Listener)} 订阅状态。
 *
 * <p>失败对外只报一次：一轮连接失败时回调 {@link Listener#onConnectFailed(String)}
 * 并对外保持 {@link State#DISCONNECTED}，后续重试静默进行，直到连接成功。
 */
public final class DeviceConnectionManager {

    public enum State {
        /** 未连接（含扫描间隙的重试等待）。 */
        DISCONNECTED,
        /** 正在定向扫描绑定设备。 */
        SCANNING,
        /** 已发现设备，正在建链与传输层握手。 */
        CONNECTING,
        /** 传输层 READY，业务会话已挂载。 */
        READY
    }

    public interface Listener {
        void onStateChanged(State state);

        /**
         * 一轮连接尝试失败（每段连续失败期只回调一次，用于 Toast 提示）。
         * 后续静默重试的失败不再回调；连接成功后重置。
         */
        void onConnectFailed(String reason);
    }

    private static final String TAG = "DeviceConnection";
    /** 扫描超时 / 断链后的轮询重试间隔。 */
    private static final long RETRY_SCAN_MS = 2_000L;
    /** 会话自愈巡检节奏（原主页 200ms UI 快照中的自愈逻辑下沉至此）。 */
    private static final long WATCHDOG_MS = 500L;

    private static DeviceConnectionManager instance;

    /** 进程级单例：绑定/解绑页面与宿主 Activity 共享同一连接生命周期。 */
    public static synchronized DeviceConnectionManager get(Context context) {
        if (instance == null) {
            instance = new DeviceConnectionManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context app;
    private final RecordingManager recManager;
    private final DeviceBindingStore bindingStore;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private BleDeviceScanner scanner;
    private BleDevice device;
    private DefaultReliableTransport transport;
    /** 仅用于断开时 shutdown（manager 负责业务侧分发）。 */
    private AppRecorderSession recSessionRef;

    /** 内部真实状态。 */
    private volatile State state = State.DISCONNECTED;
    /** 对外上报状态：静默重试期停留在 DISCONNECTED，避免卡片在状态间闪烁。 */
    private volatile State reportedState = State.DISCONNECTED;
    /** 自动轮询总开关：false 时任何事件都不再触发重试。 */
    private volatile boolean autoConnect;
    /** 本段连续失败期是否已对外上报过失败原因。 */
    private volatile boolean failureNotified;
    /** 是否处于静默重试期（首次失败之后、连接成功之前）。 */
    private volatile boolean silentRetry;
    /** 传输层是否曾就绪：区分「连接握手失败」与「连接已断开」。 */
    private volatile boolean linkReady;

    private DeviceConnectionManager(Context context) {
        this.app = context.getApplicationContext();
        this.recManager = RecordingManager.get();
        this.bindingStore = new DeviceBindingStore(context);
    }

    // ==================== 状态订阅 ====================

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 对外上报状态（页面渲染以此为准）。 */
    public State state() {
        return reportedState;
    }

    /** 自动轮询是否处于开启状态（供宿主在 onResume 时恢复自动连接）。 */
    public boolean isRunning() {
        return autoConnect;
    }

    public DeviceBindingStore bindingStore() {
        return bindingStore;
    }

    // ==================== 绑定驱动 ====================

    /**
     * 进入自动模式：存在绑定设备时立即开始定向扫描，失败/断链后按节奏持续重试。
     * 未绑定设备时不做任何动作（等待绑定页完成绑定后再次调用）。
     */
    public void startAutoConnect() {
        if (autoConnect) {
            return;
        }
        if (!bindingStore.hasBinding()) {
            AppLog.i(TAG, "未绑定设备，等待绑定后再启动自动连接");
            return;
        }
        autoConnect = true;
        failureNotified = false;
        silentRetry = false;
        AppLog.i(TAG, "开始自动连接已绑定设备");
        startScanCycle();
        scheduleWatchdog();
    }

    /** 解除绑定：清除本地绑定信息并拆除全部连接资源。 */
    public void unbind() {
        AppLog.i(TAG, "解除设备绑定");
        bindingStore.clear();
        shutdown();
    }

    /** 停止轮询并拆除全部连接资源；Activity 销毁时调用。 */
    public void shutdown() {
        autoConnect = false;
        ui.removeCallbacksAndMessages(null);
        teardown();
        failureNotified = false;
        silentRetry = false;
        setState(State.DISCONNECTED);
    }

    /** 业务协议错误通知；RequestManager 已发起断链，onLinkDown 会完成重连收尾。 */
    public void onProtocolError(String reason) {
        AppLog.e(TAG, "业务协议错误，等待链路终止并准备重连：" + reason);
    }

    // ==================== 自动轮询 ====================

    private void startScanCycle() {
        if (!autoConnect || scanner != null || device != null || transport != null) {
            return;
        }
        DeviceBindingStore.Binding binding = bindingStore.get();
        if (binding == null) {
            // 绑定信息缺失（例如刚解绑）：停止自动模式。
            autoConnect = false;
            return;
        }
        scanner = new BleDeviceScanner(app, scannerEvents);
        if (!scanner.startScanForTarget(binding.serial)) {
            scanner = null;
            failConnection("蓝牙未开启，请打开蓝牙");
            return;
        }
        setState(State.SCANNING);
        AppLog.i(TAG, "正在定向扫描绑定设备（序列号 " + binding.serial + "）……");
    }

    private void scheduleRetry() {
        if (!autoConnect) {
            return;
        }
        ui.postDelayed(() -> {
            if (autoConnect) {
                startScanCycle();
            }
        }, RETRY_SCAN_MS);
    }

    /**
     * 一轮连接尝试失败：首次失败对外上报原因并置 DISCONNECTED，
     * 之后进入静默重试，直到连接成功（READY 时复位标记）。
     */
    private void failConnection(String reason) {
        // unbind()/shutdown() 会主动拆除 GATT；其异步 onDisconnected/onLinkDown
        // 可能在拆链完成后才到达。此时不再处于自动连接模式（或绑定已清除），
        // 属于用户预期内的主动断开，绝不能作为连接失败向页面弹 Toast。
        if (!autoConnect || !bindingStore.hasBinding()) {
            AppLog.i(TAG, "忽略主动停止连接产生的迟到断链回调：" + reason);
            teardown();
            setState(State.DISCONNECTED);
            return;
        }

        teardown();
        setState(State.DISCONNECTED);
        if (!failureNotified) {
            failureNotified = true;
            notifyConnectFailed(reason);
        }
        silentRetry = true;
        scheduleRetry();
    }

    // ==================== 连接生命周期 ====================

    /**
     * 定向扫描命中绑定设备后：以该设备为链路对端启动传输层与业务会话，再发起 GATT 连接。
     * 传输层在链路就绪（Notify 启用）前不会发出任何帧，时序与改造前一致。
     */
    private void onTargetFound(DiscoveredDevice found) {
        device = found.toBleDevice(app);
        device.setEvents(deviceEvents);

        // App 侧配置：大流量方向为「设备 → App」
        TransportConfig config = TransportConfig.forApp();
        transport = new DefaultReliableTransport(
                device, config, /* initiator = */ true,
                message -> AppLog.i("BleLink", message));
        transport.setCallback(transportCallback);
        transport.start();

        AppRecorderSession session = recManager.createAndAttachSession(transport);
        recSessionRef = session;

        device.connect();
        setState(State.CONNECTING);
        AppLog.i(TAG, "发现绑定设备 " + found.productName() + " (" + found.serial() + ")，正在连接");
    }

    private void teardown() {
        recManager.detachSession("连接已断开");
        AppRecorderSession s = recSessionRef;
        recSessionRef = null;
        if (s != null) {
            s.shutdown();
        }
        if (scanner != null) {
            scanner.stopScan();
            scanner = null;
        }
        if (device != null) {
            device.disconnect();
            device = null;
        }
        if (transport != null) {
            transport.shutdown();
            transport = null;
        }
        linkReady = false;
    }

    // ==================== 会话自愈巡检 ====================

    private void scheduleWatchdog() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!autoConnect) {
                    return;
                }
                healSessionIfNeeded();
                ui.postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private void healSessionIfNeeded() {
        if (device == null || transport == null) {
            return;
        }
        AppRecorderSession s = recManager.session();
        if (s == null) {
            // 自愈：录音会话缺失（例如连接时序异常）时补建并挂载。
            s = recManager.createAndAttachSession(transport);
            recSessionRef = s;
            AppLog.w(TAG, "自愈：补建录音业务会话");
        }
        if (transport.isReady() && s.state() == AppRecorderSession.MainState.DISCONNECTED) {
            // 自愈：若错过了 onReady 回调，按 transport READY 状态补推进业务会话。
            s.onLinkReady();
        }
    }

    private void setState(State next) {
        if (state == next) {
            return;
        }
        state = next;
        // 静默重试期对外保持 DISCONNECTED，直到 READY。
        State external = silentRetry && next != State.READY ? State.DISCONNECTED : next;
        if (reportedState == external) {
            return;
        }
        reportedState = external;
        for (Listener listener : listeners) {
            listener.onStateChanged(external);
        }
    }

    private void notifyConnectFailed(String reason) {
        for (Listener listener : listeners) {
            listener.onConnectFailed(reason);
        }
    }

    // ==================== 传输层回调 ====================

    private final ReliableTransport.Callback transportCallback = new ReliableTransport.Callback() {
        @Override
        public void onReady(int maxMessageSize, boolean streamingCapable) {
            AppRecorderSession s = recManager.session();
            if (s != null) {
                // AppRecorderSession 会将 device.status 作为首个业务请求，并根据
                // 原子状态快照决定 attach 或进入离线文件同步。
                s.onLinkReady();
            }
            ui.post(() -> {
                linkReady = true;
                // 连接成功：复位失败标记，退出静默重试。
                failureNotified = false;
                silentRetry = false;
                setState(State.READY);
                AppLog.i("Transport", "传输层握手完成，可接收业务消息"
                        + (streamingCapable ? "" : "（当前 MTU 偏小，不具备实时音频能力）"));
            });
        }

        @Override
        public void onMessageReceived(byte[] message) {
            // 传输层已保证：完整、有序、不重复。路由到录音会话。
            AppRecorderSession s = recManager.session();
            if (s != null) {
                s.onMessage(message);
            }
        }

        @Override
        public void onReadyToSend() {
            // App 侧无数据源需要节流（业务规则保证最多一个 pending Request）。
        }

        @Override
        public void onSendStalled(long stalledForMs) {
            AppRecorderSession s = recManager.session();
            if (s != null) {
                s.onTransportSendStalled();
            }
        }

        @Override
        public void onSendResumed(long stalledForMs) {
            AppRecorderSession s = recManager.session();
            if (s != null) {
                s.onTransportSendResumed();
            }
        }

        @Override
        public void onLinkDown(TransportFatalError reason, String message) {
            AppRecorderSession s = recManager.session();
            if (s != null) {
                s.onLinkDown();
            }
            ui.post(() -> {
                AppLog.e("Transport", "链路终止[" + reason + "]：" + message);
                if (transport != null) {
                    AppLog.i("Transport", "传输层指标 → " + transport.getMetrics().dump());
                }
                // 一刀切：关闭 GATT、销毁 transport/session，回到自动轮询。
                failConnection("连接已断开");
            });
        }

        @Override
        public void onError(TransportError error, String message) {
            AppLog.w("Transport", "传输层错误[" + error + "]：" + message);
        }
    };

    // ==================== BLE 层事件 ====================

    /** 扫描级事件：定向发现绑定设备。 */
    private final BleDeviceScanner.Events scannerEvents = new BleDeviceScanner.Events() {
        @Override
        public void onLog(String message) {
            AppLog.i("BleScan", message);
        }

        @Override
        public void onScanStarted() {
            ui.post(() -> setState(State.SCANNING));
        }

        @Override
        public void onScanTimeout() {
            ui.post(() -> {
                AppLog.w("BleScan", "定向扫描超时，未找到绑定设备");
                failConnection("未扫描到蓝牙设备");
            });
        }

        @Override
        public void onTargetFound(DiscoveredDevice found) {
            ui.post(() -> {
                if (scanner == null || device != null) {
                    return;   // 扫描期间已主动断开，丢弃迟到结果
                }
                DeviceConnectionManager.this.onTargetFound(found);
            });
        }

        @Override
        public void onDeviceDiscovered(DiscoveredDevice discoveredDevice) {
            // 定向模式不使用（发现模式由绑定页消费）。
        }

        @Override
        public void onDiscoveryFinished(List<DiscoveredDevice> devices) {
            // 定向模式不使用（发现模式由绑定页消费）。
        }
    };

    /** 连接级事件：已建立连接上的建链、MTU、断连。 */
    private final BleDevice.Events deviceEvents = new BleDevice.Events() {
        @Override
        public void onLog(String message) {
            AppLog.i("BleLink", message);
        }

        @Override
        public void onConnected() {
            ui.post(() -> setState(State.CONNECTING));
        }

        @Override
        public void onMtuChanged(int mtu) {
            AppLog.i("BleLink", "MTU：" + mtu + "（attPayload " + (mtu - 3) + " B）");
        }

        @Override
        public void onDisconnected(String reason) {
            ui.post(() -> {
                AppLog.i("BleLink", reason);

                AppRecorderSession s = recSessionRef;
                if (s != null) {
                    s.onLinkDown();
                }

                // 传输层就绪前断开视为握手失败；就绪后断开视为连接中断。
                failConnection(linkReady ? "连接已断开" : "连接握手失败");
            });
        }
    };
}
