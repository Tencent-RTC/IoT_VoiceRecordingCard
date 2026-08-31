package com.recorder.client;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.recorder.client.ble.BleDeviceScanner;
import com.recorder.client.ble.DiscoveredDevice;
import com.recorder.client.device.DeviceBindingStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定设备页（设计稿 bind_device_scanning / found_no_device / found_some_device）。
 *
 * <p>进入页面即自动启动 12s 发现模式扫描，实时罗列扫描到的设备（产品名 + 序列号）；
 * 扫描结束后点击雷达圆可再次扫描。点击设备条目的「绑定」：本地记录绑定信息、
 * 启动定向连接并退回首页（首页随即进入「连接中」态）。
 */
public final class BindDeviceActivity extends Activity {

    private static final int REQ_PERMISSION = 3001;
    private TextView tvScanState;
    private RadarScanView radarView;
    private View radarCircle;
    private LinearLayout deviceListContainer;

    private BleDeviceScanner scanner;
    /** 本页已发现设备（按发现顺序），与列表条目一一对应。 */
    private final List<DiscoveredDevice> devices = new ArrayList<>();
    /** 绑定流程已触发（防重复点击）。 */
    private volatile boolean bindingTriggered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bind_device);
        // Insets 适配依赖布局中的 bindRoot，必须在 setContentView 之后执行。
        styleSystemBars();

        tvScanState = findViewById(R.id.tvScanState);
        radarView = findViewById(R.id.radarView);
        radarCircle = findViewById(R.id.radarCircle);
        deviceListContainer = findViewById(R.id.deviceListContainer);
        // 设备行品牌色外阴影会溢出行边界，不能被列表容器裁掉
        deviceListContainer.setClipChildren(false);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        // 扫描结束（静态圆）时点击重新扫描；扫描中点击无效。
        radarCircle.setOnClickListener(v -> {
            if (scanner == null || !scanner.isScanning()) {
                startScanWithPermission();
            }
        });

        startScanWithPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }

    // ==================== 扫描流程 ====================

    private void startScanWithPermission() {
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSION);
            return;
        }
        startDiscovery();
    }

    private void startDiscovery() {
        stopScan();
        devices.clear();
        deviceListContainer.removeAllViews();
        scanner = new BleDeviceScanner(this, scannerEvents);
        if (!scanner.startDiscovery()) {
            scanner = null;
            showScanFinished();
            Toast.makeText(this, "蓝牙不可用，请检查蓝牙开关", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.stopScan();
            scanner = null;
        }
        radarView.setScanning(false);
    }

    /** 扫描中 / 扫描结束两态的副标题与雷达动画渲染。 */
    private void renderScanning() {
        radarView.setScanning(true);
        tvScanState.setText("扫描中，已找到 " + devices.size() + " 台可用设备");
    }

    private void showScanFinished() {
        radarView.setScanning(false);
        if (devices.isEmpty()) {
            tvScanState.setText("附近未扫描到任何可连接设备\n点击可再次进行扫描");
        } else {
            tvScanState.setText("扫描结束，共发现 " + devices.size() + " 台可连接设备\n点击可再次进行扫描");
        }
    }

    private void addDeviceItem(DiscoveredDevice device) {
        View item = getLayoutInflater().inflate(R.layout.item_bind_device,
                deviceListContainer, false);
        // 设备行使用统一的品牌色阴影规格。
        item.setBackground(CardShadowDrawable.accent(this, 16f));
        TextView tvProduct = item.findViewById(R.id.tvProductName);
        TextView tvSerial = item.findViewById(R.id.tvSerial);
        TextView btnBind = item.findViewById(R.id.btnBind);
        tvProduct.setText(device.productName());
        tvSerial.setText(device.serial());
        btnBind.setOnClickListener(v -> bindDevice(device));
        deviceListContainer.addView(item);
    }

    // ==================== 绑定 ====================

    /** 绑定成功：记录本地绑定信息、启动定向连接并退回首页。 */
    private void bindDevice(DiscoveredDevice device) {
        if (bindingTriggered) {
            return;
        }
        bindingTriggered = true;
        stopScan();
        DeviceConnectionManager connectionManager = DeviceConnectionManager.get(this);
        DeviceBindingStore store = connectionManager.bindingStore();
        store.save(device);
        connectionManager.startAutoConnect();
        AppLog.i("BindDevice", "已绑定设备 " + device.productName()
                + "（序列号 " + device.serial() + "）");
        finish();
    }

    // ==================== 权限 ====================

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Android 11 及以下：BLE 扫描依赖定位权限
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        return missing;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMISSION) {
            return;
        }
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "缺少蓝牙权限，无法扫描设备", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }
        startDiscovery();
    }

    // ==================== 扫描事件 ====================

    private final BleDeviceScanner.Events scannerEvents = new BleDeviceScanner.Events() {
        @Override
        public void onLog(String message) {
            AppLog.i("BleScan", message);
        }

        @Override
        public void onScanStarted() {
            runOnUiThread(BindDeviceActivity.this::renderScanning);
        }

        @Override
        public void onScanTimeout() {
            // 发现模式不使用（定向模式由连接管理器消费）。
        }

        @Override
        public void onTargetFound(DiscoveredDevice device) {
            // 发现模式不使用（定向模式由连接管理器消费）。
        }

        @Override
        public void onDeviceDiscovered(DiscoveredDevice device) {
            runOnUiThread(() -> {
                devices.add(device);
                addDeviceItem(device);
                tvScanState.setText("扫描中，已找到 " + devices.size() + " 台可用设备");
            });
        }

        @Override
        public void onDiscoveryFinished(List<DiscoveredDevice> finishedDevices) {
            runOnUiThread(BindDeviceActivity.this::showScanFinished);
        }
    };

    // ==================== 系统栏 ====================

    private void styleSystemBars() {
        SystemBars.styleLight(getWindow(), ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarPage), ThemeColorResolver.color(this,
                R.attr.recorderColorSurface));

        // 顶栏让出状态栏高度（与录音详情页同一适配模式）。
        View root = findViewById(R.id.bindRoot);
        SystemBars.applyPadding(root,
                SystemBars.padding(root, SystemBars.EDGE_TOP));
    }
}
