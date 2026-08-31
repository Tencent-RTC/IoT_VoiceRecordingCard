package com.recorder.client;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.device.DeviceBindingStore;

import java.util.Locale;

/**
 * 设备信息页（设计稿 device_info.png）。
 *
 * <p>展示已绑定设备的产品名、序列号与连接状态徽标；已连接时显示电量 / 存储，
 * 未连接时以「--」占位。底部「解除绑定」清除本地绑定并退回首页未绑定态。
 */
public final class DeviceInfoActivity extends Activity {

    private ImageView ivBadgeDot;
    private TextView tvBadgeState;
    private View connBadge;
    private TextView tvBattery;
    private TextView tvStorage;

    private DeviceConnectionManager connectionManager;
    private RecordingManager recordingManager;
    private AppRecorderSession.DeviceSnapshot deviceSnapshot;

    private final RecordingManager.DeviceStatusListener deviceStatusListener = snapshot -> {
        deviceSnapshot = snapshot;
        if (connectionManager != null) {
            renderConnectionState(connectionManager.state());
        }
    };

    private final DeviceConnectionManager.Listener connectionListener =
            new DeviceConnectionManager.Listener() {
                @Override
                public void onStateChanged(DeviceConnectionManager.State state) {
                    renderConnectionState(state);
                }

                @Override
                public void onConnectFailed(String reason) {
                    // 失败 Toast 由首页统一提示，本页只刷新徽标。
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);
        // Insets 适配依赖布局中的 deviceInfoRoot，必须在 setContentView 之后执行。
        styleSystemBars();

        // 设备卡使用统一的强调阴影规格。
        findViewById(R.id.deviceCard).setBackground(CardShadowDrawable.prominent(this, 16f));

        // 解除绑定按钮在原 ripple 背景下叠加危险操作阴影。
        findViewById(R.id.btnUnbind).setBackground(new LayerDrawable(new Drawable[]{
                new ShadowedDrawable(this, ThemeColorResolver.color(this,
                        R.attr.recorderColorDanger), false, 28f, 10f, 24f,
                        ThemeColorResolver.color(this, R.attr.recorderColorShadowDanger)),
                getResources().getDrawable(R.drawable.bg_unbind_button, getTheme())}));

        connectionManager = DeviceConnectionManager.get(this);
        recordingManager = RecordingManager.get();
        deviceSnapshot = recordingManager.deviceSnapshot();
        DeviceBindingStore.Binding binding = connectionManager.bindingStore().get();
        if (binding == null) {
            // 无绑定设备时不应进入本页。
            finish();
            return;
        }

        connBadge = findViewById(R.id.connBadge);
        ivBadgeDot = findViewById(R.id.ivBadgeDot);
        tvBadgeState = findViewById(R.id.tvBadgeState);
        tvBattery = findViewById(R.id.tvBattery);
        tvStorage = findViewById(R.id.tvStorage);

        ((TextView) findViewById(R.id.tvProductName)).setText(binding.productName);
        ((TextView) findViewById(R.id.tvSerial)).setText(binding.serial);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnUnbind).setOnClickListener(v -> {
            connectionManager.unbind();
            finish();
        });

        renderConnectionState(connectionManager.state());
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectionManager.addListener(connectionListener);
        recordingManager.addDeviceStatusListener(deviceStatusListener);
        renderConnectionState(connectionManager.state());
    }

    @Override
    protected void onPause() {
        super.onPause();
        connectionManager.removeListener(connectionListener);
        recordingManager.removeDeviceStatusListener(deviceStatusListener);
    }

    private void renderConnectionState(DeviceConnectionManager.State state) {
        boolean connected = state == DeviceConnectionManager.State.READY;
        ivBadgeDot.setImageResource(connected
                ? R.drawable.bg_status_dot_connected : R.drawable.bg_status_dot_offline);
        tvBadgeState.setText(connected ? "已连接" : "未连接");
        tvBadgeState.setTextColor(ThemeColorResolver.color(this, connected
                ? R.attr.recorderColorStatusConnected : R.attr.recorderColorTextSecondary));
        connBadge.setBackgroundResource(connected
                ? R.drawable.bg_conn_badge : R.drawable.bg_conn_badge_offline);
        AppRecorderSession.DeviceSnapshot snapshot = connected ? deviceSnapshot : null;
        tvBattery.setText(snapshot == null ? "--" : snapshot.batteryPercentage + "%");
        if (snapshot == null) {
            tvStorage.setText("--");
        } else {
            long available = Math.max(0L,
                    snapshot.totalStorageBytes - snapshot.usedStorageBytes);
            String storageText = formatBytesWithoutGbUnit(available) + " / "
                    + formatBytesWithGbUnit(snapshot.totalStorageBytes);
            tvStorage.setText(storageText);
        }
    }

    private static String formatBytesWithoutGbUnit(long bytes) {
        return String.format(Locale.getDefault(), "%.1f",
                bytes / (1024d * 1024d * 1024d));
    }

    private static String formatBytesWithGbUnit(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB",
                bytes / (1024d * 1024d * 1024d));
    }

    // ==================== 系统栏 ====================

    private void styleSystemBars() {
        SystemBars.styleLight(getWindow(), ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarPage), ThemeColorResolver.color(this,
                R.attr.recorderColorSurface));

        // 根布局同时让出顶部与底部系统栏；XML 中按钮原有的 24dp 底部间距会保留。
        View root = findViewById(R.id.deviceInfoRoot);
        SystemBars.applyPadding(root, SystemBars.padding(root,
                SystemBars.EDGE_TOP | SystemBars.EDGE_BOTTOM));
    }
}
