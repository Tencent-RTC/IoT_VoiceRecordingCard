package com.recorder.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.recorder.client.offline.OfflineSyncController;
import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;
import com.recorder.client.offline.RecordingNameFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * recorder client 单 Activity 壳。
 *
 * <p>承载两个一级页面 Destination：「录音」（{@link HomeScreenView}，默认页）
 * 与「录音文件」（{@link FileListScreenView}），通过底部固定导航栏
 * （{@link BottomNavView}）切换；后续"我的"页面按同样模式扩展。
 *
 * <p>连接生命周期已下沉至 {@link DeviceConnectionManager}（绑定驱动）：已绑定
 * 设备时 App 打开后自动定向扫描并连接，断链后自动静默重连；未绑定时不扫描，
 * 等待用户在绑定页完成绑定。录音会话与 ASR 状态由 {@link RecordingManager}
 * 持有；两个页面共享同一份 {@link RecordingHistoryRepository} 历史快照。
 */
public final class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSION = 2001;
    private static final int UI_REFRESH_MS = 200;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override
        public void run() {
            if (homePage == null || recManager == null) {
                return;
            }
            homePage.setStartRecordingEnabled(recManager.canStartRecording());
            ui.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private FrameLayout pageContainer;
    private FrameLayout interactionOverlay;
    private BottomNavView bottomNav;
    private HomeScreenView homePage;
    private FileListScreenView filesPage;

    private RecordingManager recManager;
    private RecordingHistoryRepository historyRepository;
    private DeviceConnectionManager connectionManager;
    private boolean realtimePageLaunching;
    private final RecordingManager.DeviceStatusListener deviceStatusListener =
            snapshot -> homePage.renderDeviceStatus(snapshot);
    private final RecordingManager.ReverseControlListener reverseControlListener =
            recordingId -> launchRealtimeRecording(true, recordingId);

    private volatile RecordingHistoryRepository.HistorySnapshot historySnapshot;
    /** 兼容 Android 8 及厂商键盘：根据可见窗口实时把重命名面板顶到 IME 上沿。 */
    private ViewTreeObserver.OnGlobalLayoutListener renameKeyboardLayoutListener;
    private RecordingContextMenuController contextMenuController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        styleSystemBars();
        setContentView(R.layout.activity_main);

        pageContainer = findViewById(R.id.pageContainer);
        interactionOverlay = findViewById(R.id.interactionOverlay);
        contextMenuController = new RecordingContextMenuController(this, interactionOverlay,
                this::clearInteractionOverlayContent,
                new RecordingContextMenuController.Listener() {
                    @Override
                    public void onRenameRequested(RecordingHistoryStore.Entry entry) {
                        showRenamePanel(entry);
                    }

                    @Override
                    public void onDeleteRequested(RecordingHistoryStore.Entry entry) {
                        requestDeleteLocalHistory(entry);
                    }
                });
        bottomNav = findViewById(R.id.bottomNav);
        applySystemBarInsets();

        homePage = new HomeScreenView(this);
        filesPage = new FileListScreenView(this);
        pageContainer.addView(homePage);
        pageContainer.addView(filesPage);
        filesPage.setVisibility(View.GONE);

        bottomNav.setOnTabSelectedListener(this::showPage);
        homePage.setOnStartRecordingListener(() -> launchRealtimeRecording(false, 0L));
        homePage.setOnShowAllListener(() -> {
            showPage(BottomNavView.TAB_FILES);
            bottomNav.setSelectedTab(BottomNavView.TAB_FILES);
        });

        recManager = RecordingManager.get();
        recManager.init(this);
        recManager.addDeviceStatusListener(deviceStatusListener);
        recManager.addReverseControlListener(reverseControlListener);
        historyRepository = recManager.historyRepository();
        historySnapshot = historyRepository.snapshot();
        homePage.setOnHistoryEntryLongClickListener(this::showContextMenu);
        filesPage.setOnHistoryEntryLongClickListener(this::showContextMenu);
        historyRepository.addListener(historyListener);
        recManager.setConnectionErrorHandler(reason -> {
            // RequestManager 已主动断链；这里只记录原因，链路回调负责自动轮询。
            connectionManager.onProtocolError(reason);
        });
        connectionManager = DeviceConnectionManager.get(this);
        connectionManager.addListener(connectionListener);
        renderHomeDeviceCard();

        scheduleUiRefresh();
        startAutoConnectWithPermission();
        AppLog.i(TAG, "应用已启动");
    }

    /** App 点击与设备反向接入共用同一个主线程门闩，保证一轮录音只打开一个实时页。 */
    private void launchRealtimeRecording(boolean attachOnly, long recordingId) {
        if (realtimePageLaunching || isFinishing()) {
            AppLog.i(TAG, "忽略重复实时录音页启动：attachOnly=" + attachOnly
                    + ", recordingId=" + recordingId);
            return;
        }
        realtimePageLaunching = true;
        homePage.setStartRecordingEnabled(false);
        Intent intent = new Intent(this, RealtimeRecordingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (attachOnly) {
            intent.putExtra(RealtimeRecordingActivity.EXTRA_ATTACH_ONLY, true);
        }
        AppLog.i(TAG, "打开实时录音页：attachOnly=" + attachOnly
                + ", recordingId=" + recordingId);
        startActivity(intent);
    }

    /** 按当前绑定信息与连接状态刷新首页设备卡片。 */
    private void renderHomeDeviceCard() {
        homePage.renderDeviceCard(connectionManager.bindingStore().get(),
                connectionManager.state());
    }

    /** 连接状态变化驱动首页卡片；失败原因以 Toast 提示（每段连续失败期仅一次）。 */
    private final DeviceConnectionManager.Listener connectionListener =
            new DeviceConnectionManager.Listener() {
                @Override
                public void onStateChanged(DeviceConnectionManager.State state) {
                    renderHomeDeviceCard();
                }

                @Override
                public void onConnectFailed(String reason) {
                    Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        realtimePageLaunching = false;
        // 绑定页 / 设备信息页返回后，绑定信息可能已变化，重新渲染设备卡片。
        renderHomeDeviceCard();
        // 从系统设置页补授权限返回后，恢复自动连接。
        if (connectionManager != null && !connectionManager.isRunning()
                && missingPermissions().isEmpty()) {
            connectionManager.startAutoConnect();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(uiRefresh);
        super.onDestroy();

        if (contextMenuController != null) {
            contextMenuController.destroy();
        }
        stopWatchingRenameKeyboard();

        if (historyRepository != null) {
            historyRepository.removeListener(historyListener);
        }
        if (recManager != null) {
            recManager.removeDeviceStatusListener(deviceStatusListener);
            recManager.removeReverseControlListener(reverseControlListener);
        }
        connectionManager.removeListener(connectionListener);
        connectionManager.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (interactionOverlay != null && interactionOverlay.getVisibility() == View.VISIBLE) {
            hideInteractionOverlay();
            return;
        }
        super.onBackPressed();
    }

    // ==================== 页面 Destination 切换 ====================

    private int currentTab = BottomNavView.TAB_RECORD;

    private void showPage(int tab) {
        currentTab = tab;
        homePage.setVisibility(tab == BottomNavView.TAB_FILES ? View.GONE : View.VISIBLE);
        filesPage.setVisibility(tab == BottomNavView.TAB_FILES ? View.VISIBLE : View.GONE);
        // 隐藏页不跟随快照刷新（离线下载期间快照以逐 chunk 频率到达），
        // 切页时用最新快照一次性补齐。
        bindHistoryPages(historySnapshot);
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

    private void startAutoConnectWithPermission() {
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            AppLog.i(TAG, "正在申请蓝牙/定位权限……");
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSION);
            return;
        }
        connectionManager.startAutoConnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMISSION) {
            return;
        }
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, "权限被拒绝，无法扫描蓝牙设备");
                return;
            }
        }
        connectionManager.startAutoConnect();
    }

    // ==================== 历史快照分发 ====================

    private final RecordingHistoryRepository.Listener historyListener = snapshot -> {
        historySnapshot = snapshot;
        bindHistoryPages(snapshot);
    };

    /** 仅渲染当前可见页；隐藏页保持静止，见 {@link #showPage(int)}。 */
    private void bindHistoryPages(RecordingHistoryRepository.HistorySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (currentTab == BottomNavView.TAB_FILES) {
            filesPage.bindHistory(snapshot);
        } else {
            homePage.bindHistory(snapshot);
        }
    }

    // ==================== 历史记录的上下文菜单、重命名与删除 ====================

    /** 长按后的菜单由独立控制器承载，Activity 只提供业务操作回调。 */
    private boolean showContextMenu(RecordingHistoryStore.Entry entry, View anchor) {
        return contextMenuController != null && contextMenuController.show(entry, anchor);
    }

    private void showRenamePanel(RecordingHistoryStore.Entry entry) {
        if (entry == null || interactionOverlay == null) {
            return;
        }
        prepareInteractionOverlay();
        RecordingRenamePanel panel = new RecordingRenamePanel(this);
        panel.bind(entry, RecordingNameFormatter.displayName(entry.recordingName,
                entry.createdTimeSec), new RecordingRenamePanel.Listener() {
            @Override
            public void onRenameConfirmed(RecordingHistoryStore.Entry selected,
                                          String recordingName) {
                RecordingHistoryRepository repository = historyRepository;
                if (repository == null) {
                    panel.setSubmitting(false);
                    Toast.makeText(MainActivity.this, "本地历史服务尚未初始化",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                repository.renameRecording(selected.fileName, recordingName,
                        new RecordingHistoryRepository.RenameCallback() {
                            @Override
                            public void onRenamed() {
                                hideInteractionOverlay();
                                Toast.makeText(MainActivity.this, "已重命名", Toast.LENGTH_SHORT)
                                        .show();
                            }

                            @Override
                            public void onFailed(String reason) {
                                panel.setSubmitting(false);
                                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT)
                                        .show();
                            }
                        });
            }

            @Override
            public void onCanceled() {
                hideInteractionOverlay();
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM);
        interactionOverlay.addView(panel, params);
        interactionOverlay.setVisibility(View.VISIBLE);
        watchKeyboardForRenamePanel(panel);
    }

    /** 为重命名面板添加点击即可关闭的暗色蒙层。 */
    private void prepareInteractionOverlay() {
        clearInteractionOverlayContent();
        View scrim = new View(this);
        scrim.setBackgroundColor(ThemeColorResolver.color(this,
                R.attr.recorderColorOverlayScrim));
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> hideInteractionOverlay());
        interactionOverlay.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void hideInteractionOverlay() {
        hideInteractionOverlay(null);
    }

    private void hideInteractionOverlay(Runnable onHidden) {
        if (interactionOverlay == null) {
            if (onHidden != null) {
                onHidden.run();
            }
            return;
        }
        if (contextMenuController != null && contextMenuController.hide(onHidden)) {
            return;
        }
        clearInteractionOverlayContent();
        interactionOverlay.setVisibility(View.INVISIBLE);
        if (onHidden != null) {
            onHidden.run();
        }
    }

    /**
     * 只替换浮层内容，不切换可见性或系统栏颜色。菜单切换到重命名面板时蒙层连续存在，
     * 避免 Window/状态栏发生两次合成而产生肉眼可见的闪烁。
     */
    private void clearInteractionOverlayContent() {
        stopWatchingRenameKeyboard();
        for (int index = 0; index < interactionOverlay.getChildCount(); index++) {
            View child = interactionOverlay.getChildAt(index);
            if (child instanceof RecordingRenamePanel) {
                ((RecordingRenamePanel) child).dismissKeyboard();
            }
        }
        interactionOverlay.removeAllViews();
    }

    /**
     * {@code adjustResize} 是主路径；部分厂商系统仍会让 IME 覆盖应用内容，因此再根据
     * DecorView 可见区域计算实际遮挡量。窗口已被 resize 时该值自然为 0，不会重复上移。
     */
    private void watchKeyboardForRenamePanel(RecordingRenamePanel panel) {
        View decor = getWindow().getDecorView();
        renameKeyboardLayoutListener = () -> {
            if (panel.getParent() != interactionOverlay) {
                return;
            }
            Rect visibleWindow = new Rect();
            decor.getWindowVisibleDisplayFrame(visibleWindow);
            int[] overlayLocation = new int[2];
            interactionOverlay.getLocationOnScreen(overlayLocation);
            int overlayBottomOnScreen = overlayLocation[1] + interactionOverlay.getHeight();
            int keyboardOverlap = Math.max(0, overlayBottomOnScreen - visibleWindow.bottom);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (params.bottomMargin != keyboardOverlap) {
                params.bottomMargin = keyboardOverlap;
                panel.setLayoutParams(params);
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(renameKeyboardLayoutListener);
        decor.requestApplyInsets();
    }

    private void stopWatchingRenameKeyboard() {
        if (renameKeyboardLayoutListener == null) {
            return;
        }
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(renameKeyboardLayoutListener);
        }
        renameKeyboardLayoutListener = null;
    }

    /**
     * 删除入口先保留协议与本地转写的安全门：未传完、等待设备确认或转写活动中的
     * 录音一律不打开确认框，避免用户误认为操作已生效。
     */
    private void requestDeleteLocalHistory(RecordingHistoryStore.Entry entry) {
        if (!RecordingListStatusPolicy.canDelete(
                entry.transferState == RecordingHistoryStore.TransferState.TRANSMITTED,
                entry.deviceDeletionConfirmed, entry.transcriptionState.isActive())) {
            hideInteractionOverlay();
            Toast.makeText(this, "任务执行中，无法删除", Toast.LENGTH_SHORT).show();
            return;
        }
        showDeleteConfirmPanel(entry);
    }

    /** 使用共享浮层承载定制确认卡片 */
    private void showDeleteConfirmPanel(RecordingHistoryStore.Entry entry) {
        if (interactionOverlay == null) {
            return;
        }
        prepareInteractionOverlay();
        ConfirmDialogPanel panel = new ConfirmDialogPanel(this);
        String displayName = RecordingNameFormatter.displayName(entry.recordingName,
                entry.createdTimeSec);
        panel.bind("删除录音", "确定要删除「" + displayName + "」吗？删除后无法恢复。",
                "取消", "删除", new ConfirmDialogPanel.Listener() {
                    @Override
                    public void onConfirmed() {
                        hideInteractionOverlay(() -> deleteLocalHistory(entry));
                    }

                    @Override
                    public void onCanceled() {
                        hideInteractionOverlay();
                    }
                });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        int horizontalMargin = Math.round(30 * getResources().getDisplayMetrics().density);
        params.setMargins(horizontalMargin, 0, horizontalMargin, 0);
        interactionOverlay.addView(panel, params);
        interactionOverlay.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .start();
    }

    private void deleteLocalHistory(RecordingHistoryStore.Entry entry) {
        OfflineSyncController offline = recManager == null ? null : recManager.offlineSync();
        if (offline == null) {
            Toast.makeText(this, "本地历史服务尚未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        offline.deleteCompletedLocalHistory(entry,
                new OfflineSyncController.LocalHistoryDeletionListener() {
                    @Override
                    public void onDeleted() {
                        Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailed(String reason) {
                        Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ==================== UI 定时快照 ====================

    /**
     * 按 200ms 节流刷新录音入口可用性（链路 + VoiceAI 音频管线门控）。
     */
    private void scheduleUiRefresh() {
        ui.removeCallbacks(uiRefresh);
        ui.postDelayed(uiRefresh, UI_REFRESH_MS);
    }

    // ==================== 系统栏 ====================

    private void styleSystemBars() {
        SystemBars.styleLight(getWindow(), ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarPage), ThemeColorResolver.color(this,
                R.attr.recorderColorSurface));
    }

    /**
     * 系统栏自适应（Android 原生 WindowInsets 方案）：页面容器顶部让出状态栏
     * 高度、底部导航栏底部让出系统导航栏（手势条 / 三键）高度。Insets 由系统
     * 按设备与导航模式下发，随配置变化自动重排。
     */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootLayout);
        SystemBars.applyPadding(root,
                SystemBars.padding(pageContainer, SystemBars.EDGE_TOP),
                SystemBars.padding(bottomNav, SystemBars.EDGE_BOTTOM));
    }
}
