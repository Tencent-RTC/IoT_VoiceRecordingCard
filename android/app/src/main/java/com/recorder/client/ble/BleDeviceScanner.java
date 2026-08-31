package com.recorder.client.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.recorder.transport.BleUuids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BLE 设备扫描器：负责发现蓝牙录音笔设备。
 *
 * <p>本类只承担「发现」这一件事：按 Service UUID 扫描、超时控制，把结果包装为
 * {@link DiscoveredDevice} 交给调用方。后续的 GATT 连接与数据收发均与本类无关。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li>发现模式 {@link #startDiscovery()}：绑定页使用。12s 内持续聚合所有匹配
 *       Service UUID 的设备（按地址去重），每发现一台实时回调，超时后上报全量列表；</li>
 *   <li>定向模式 {@link #startScanForTarget(String)}：已绑定设备的定向重连使用。
 *       仅当设备的序列号与目标序列号相等时命中，命中即停扫并静置后交出设备；
 *       超时未命中则回调 {@link Events#onScanTimeout()}。</li>
 * </ul>
 */
public final class BleDeviceScanner {

    private static final long SCAN_TIMEOUT_MS = 12_000L;

    /**
     * 命中后到交出设备之间的静置时间。
     *
     * <p>{@code stopScan()} 是异步的：请求返回时控制器往往仍在扫描。
     * 而 {@code SCAN_MODE_LOW_LATENCY} 的扫描占空比接近 100%，
     * 若立刻发起 GATT 连接，建链与服务发现要和残余扫描抢射频时隙，
     * 表现为偶发地卡在「正在发现服务」。250ms 用户不可感知，收益明确。
     */
    private static final long STOP_SCAN_SETTLE_MS = 250L;

    private enum Mode {
        /** 发现模式：聚合全部匹配设备。 */
        DISCOVERY,
        /** 定向模式：只命中指定序列号的设备。 */
        TARGET
    }

    public interface Events {
        void onLog(String message);

        void onScanStarted();

        /** 定向模式：一轮扫描结束仍未找到目标设备。 */
        void onScanTimeout();

        /** 定向模式：找到目标设备（扫描已停止），可对其建立连接。 */
        void onTargetFound(DiscoveredDevice device);

        /** 发现模式：每发现一台新设备（按地址去重）实时回调，扫描仍在继续。 */
        void onDeviceDiscovered(DiscoveredDevice device);

        /** 发现模式：一轮扫描结束，上报期间发现的全部设备（按发现顺序）。 */
        void onDiscoveryFinished(List<DiscoveredDevice> devices);
    }

    private final Context context;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private volatile boolean scanning;
    private Mode mode = Mode.TARGET;
    /** 定向模式的目标序列号（忽略大小写匹配）。 */
    private String targetSerial;
    /** 发现模式：按地址去重的已发现设备（保持发现顺序）。 */
    private final Map<String, DiscoveredDevice> discovered = new LinkedHashMap<>();
    /** 已命中、等待扫描器静置后再交给调用方的任务。 */
    private volatile Runnable pendingFoundTask;

    private final Runnable scanTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (scanning) {
                stopScan();
                if (mode == Mode.DISCOVERY) {
                    events.onDiscoveryFinished(new ArrayList<>(discovered.values()));
                } else {
                    events.onScanTimeout();
                }
            }
        }
    };

    public BleDeviceScanner(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
    }

    /** 发现模式：12s 内持续聚合匹配设备，超时后回调全量列表。 */
    public boolean startDiscovery() {
        return startInternal(Mode.DISCOVERY, null);
    }

    /** 定向模式：只命中序列号等于 {@code serial} 的设备，命中即停。 */
    public boolean startScanForTarget(String serial) {
        return startInternal(Mode.TARGET, serial);
    }

    @SuppressLint("MissingPermission")
    private boolean startInternal(Mode nextMode, String serial) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) {
            events.onLog("本机不支持蓝牙");
            return false;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (!adapter.isEnabled()) {
            events.onLog("蓝牙未开启，请先打开蓝牙");
            return false;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            events.onLog("无法获取 BLE 扫描器");
            return false;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleUuids.SERVICE))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        mode = nextMode;
        targetSerial = serial;
        discovered.clear();
        scanning = true;
        events.onScanStarted();
        events.onLog("开始扫描 Service UUID " + BleUuids.SERVICE
                + (nextMode == Mode.TARGET ? "（定向序列号 " + serial + "）" : "（发现模式）"));
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        main.postDelayed(scanTimeoutTask, SCAN_TIMEOUT_MS);
        return true;
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        main.removeCallbacks(scanTimeoutTask);
        Runnable pending = pendingFoundTask;
        if (pending != null) {
            pendingFoundTask = null;
            main.removeCallbacks(pending);
        }
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
                // 蓝牙已关闭等
            }
        }
        scanning = false;
    }

    public boolean isScanning() {
        return scanning;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) {
                return;
            }

            final DiscoveredDevice found = DiscoveredDevice.from(result);
            events.onLog("发现设备 product=" + found.productName()
                    + " serial=" + found.serial() + " address=" + found.address());

            if (mode == Mode.DISCOVERY) {
                // 发现模式：按地址去重聚合，扫描持续到超时为止。
                if (!discovered.containsKey(found.address())) {
                    discovered.put(found.address(), found);
                    events.onDeviceDiscovered(found);
                }
                return;
            }

            // 定向模式：仅命中目标序列号的设备。
            if (targetSerial == null || !targetSerial.equalsIgnoreCase(found.serial())) {
                return;
            }

            // 先停止扫描，静置一段时间后再把设备交给调用方建立连接。
            stopScan();
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    pendingFoundTask = null;
                    events.onTargetFound(found);
                }
            };
            pendingFoundTask = task;
            events.onLog("已停止扫描，等待 " + STOP_SCAN_SETTLE_MS + "ms 后发起连接");
            main.postDelayed(task, STOP_SCAN_SETTLE_MS);
        }

        @Override
        public void onScanFailed(int errorCode) {
            events.onLog("扫描失败，错误码 " + errorCode);
            // 与超时同路径收尾，避免调用方悬挂在扫描中状态。
            if (scanning) {
                stopScan();
                if (mode == Mode.DISCOVERY) {
                    events.onDiscoveryFinished(new ArrayList<>(discovered.values()));
                } else {
                    events.onScanTimeout();
                }
            }
        }
    };
}
