package com.recorder.client;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.device.DeviceBindingStore;
import com.recorder.client.offline.RecordingHistoryRepository;

import java.util.Calendar;
import java.util.Locale;

/**
 * 主页面（底部导航"录音" Destination）。
 *
 * <p>上方为设备卡片，按绑定状态与连接状态呈现四态：
 * 未绑定（绑定引导卡，"+"进入绑定页）/ 连接中（黄点）/ 已连接（完整卡片 +
 * 录音入口）/ 未连接（灰点）。已绑定任意状态下点击设备卡片进入设备信息页。
 * 电量/存储由建链后的 device.status 快照驱动。
 *
 * <p>下方"最近记录"与"录音文件"页共享同一份
 * {@link RecordingHistoryRepository} 历史快照。
 */
public final class HomeScreenView extends FrameLayout {

    /** 主页面最多展示的最近记录条数。 */
    private static final int RECENT_LIMIT = 3;

    public interface OnShowAllListener {
        void onShowAll();
    }

    public interface OnStartRecordingListener {
        void onStartRecording();
    }

    private OnStartRecordingListener startRecordingListener;
    private final View bindGuideContent;
    private final View boundDeviceContent;
    private final TextView tvProductName;
    private final ImageView ivConnDot;
    private final TextView tvConnState;
    private final View batteryStorageGroup;
    private final View recordDivider;
    private final View recordSection;
    private final View btnStartRec;
    private final TextView tvBattery;
    private final TextView tvStorage;
    private final RecordingListView recentList;
    private final TextView tvRecentEmpty;

    public HomeScreenView(Context context) {
        this(context, null);
    }

    public HomeScreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.screen_home, this, true);

        TextView tvGreeting = findViewById(R.id.tvGreeting);
        // 头像按钮使用主题表面色与强调阴影。
        findViewById(R.id.avatarButton).setBackground(new ShadowedDrawable(context,
                ThemeColorResolver.color(context, R.attr.recorderColorSurface), true,
                0f, 4f, 12f, ThemeColorResolver.color(context,
                R.attr.recorderColorShadowNeutralMedium)));
        // 设备卡使用统一的强调阴影规格。
        findViewById(R.id.deviceCard).setBackground(CardShadowDrawable.prominent(context, 16f));
        bindGuideContent = findViewById(R.id.bindGuideContent);
        boundDeviceContent = findViewById(R.id.boundDeviceContent);
        tvProductName = findViewById(R.id.tvProductName);
        ivConnDot = findViewById(R.id.ivConnDot);
        tvConnState = findViewById(R.id.tvConnState);
        batteryStorageGroup = findViewById(R.id.batteryStorageGroup);
        recordDivider = findViewById(R.id.recordDivider);
        recordSection = findViewById(R.id.recordSection);
        btnStartRec = findViewById(R.id.btnStartRec);
        tvBattery = findViewById(R.id.tvBattery);
        tvStorage = findViewById(R.id.tvStorage);
        recentList = findViewById(R.id.recentList);
        tvRecentEmpty = findViewById(R.id.tvRecentEmpty);

        tvGreeting.setText(greetingOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        recentList.setMaxItems(RECENT_LIMIT);

        // 未绑定："+" 进入绑定页并自动开始扫描。
        findViewById(R.id.btnBindDevice).setOnClickListener(v ->
                context.startActivity(new Intent(context, BindDeviceActivity.class)));
        // 已绑定：仅设备卡上半部分（图标/名称/状态/电量存储区）可点击进入设备信息页；
        // 下半部分「轻点开始录音」区不响应（录音 FAB 仍自行消费点击）。
        findViewById(R.id.deviceInfoRow).setOnClickListener(v ->
                context.startActivity(new Intent(context, DeviceInfoActivity.class)));
        btnStartRec.setOnClickListener(v -> {
            if (startRecordingListener != null) {
                startRecordingListener.onStartRecording();
            }
        });
    }

    public void setOnStartRecordingListener(OnStartRecordingListener listener) {
        startRecordingListener = listener;
    }

    public void setOnShowAllListener(OnShowAllListener listener) {
        findViewById(R.id.tvShowAll).setOnClickListener(v -> {
            if (listener != null) {
                listener.onShowAll();
            }
        });
    }

    public void setOnHistoryEntryLongClickListener(
            RecordingListView.OnEntryLongClickListener listener) {
        recentList.setOnEntryLongClickListener(listener);
    }

    /**
     * 按绑定信息与连接状态渲染设备卡片四态。
     *
     * @param binding 当前绑定（null 表示未绑定）
     * @param state   连接状态（仅绑定存在时有意义）
     */
    public void renderDeviceCard(DeviceBindingStore.Binding binding,
                                 DeviceConnectionManager.State state) {
        if (binding == null) {
            bindGuideContent.setVisibility(VISIBLE);
            boundDeviceContent.setVisibility(GONE);
            return;
        }
        bindGuideContent.setVisibility(GONE);
        boundDeviceContent.setVisibility(VISIBLE);
        tvProductName.setText(binding.productName);

        boolean ready = state == DeviceConnectionManager.State.READY;
        switch (state) {
            case READY:
                ivConnDot.setImageResource(R.drawable.bg_status_dot_connected);
                tvConnState.setText("已连接");
                tvConnState.setTextColor(ThemeColorResolver.color(getContext(),
                        R.attr.recorderColorStatusConnected));
                break;
            case SCANNING:
            case CONNECTING:
                ivConnDot.setImageResource(R.drawable.bg_status_dot_connecting);
                tvConnState.setText("连接中...");
                tvConnState.setTextColor(ThemeColorResolver.color(getContext(),
                        R.attr.recorderColorStatusConnecting));
                break;
            case DISCONNECTED:
            default:
                ivConnDot.setImageResource(R.drawable.bg_status_dot_offline);
                tvConnState.setText("未连接");
                tvConnState.setTextColor(ThemeColorResolver.color(getContext(),
                        R.attr.recorderColorTextSecondary));
                break;
        }
        // 电量/存储与录音入口仅已连接时可见（连接中/未连接为紧凑卡片）。
        int readyVisibility = ready ? VISIBLE : GONE;
        batteryStorageGroup.setVisibility(readyVisibility);
        recordDivider.setVisibility(readyVisibility);
        recordSection.setVisibility(readyVisibility);
    }

    public void renderDeviceStatus(AppRecorderSession.DeviceSnapshot snapshot) {
        if (snapshot == null) {
            tvBattery.setText("--");
            tvStorage.setText("--");
            return;
        }
        tvBattery.setText(snapshot.batteryPercentage + "%");
        long available = Math.max(0L,
                snapshot.totalStorageBytes - snapshot.usedStorageBytes);
        tvStorage.setText(formatBytesWithoutGbUnit(available) + " / "
                + formatBytesWithGbUnit(snapshot.totalStorageBytes));
    }

    private static String formatBytesWithoutGbUnit(long bytes) {
        return String.format(Locale.getDefault(), "%.1f",
                bytes / (1024d * 1024d * 1024d));
    }

    private static String formatBytesWithGbUnit(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB",
                bytes / (1024d * 1024d * 1024d));
    }

    /** 由宿主的定时快照驱动：链路与 VoiceAI 音频管线就绪时才开放录音入口。 */
    public void setStartRecordingEnabled(boolean enabled) {
        btnStartRec.setEnabled(enabled);
    }

    public void bindHistory(RecordingHistoryRepository.HistorySnapshot snapshot) {
        recentList.setSnapshot(snapshot);
        tvRecentEmpty.setVisibility(snapshot.entries.isEmpty() ? VISIBLE : GONE);
    }

    public void refreshHistory() {
        recentList.refresh();
    }

    private static String greetingOf(int hour) {
        if (hour >= 5 && hour < 12) {
            return "上午好";
        }
        if (hour >= 12 && hour < 18) {
            return "下午好";
        }
        return "晚上好";
    }
}
