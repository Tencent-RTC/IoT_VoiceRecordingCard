package com.recorder.client;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.recorder.business.AppRecorderSession;
import com.recorder.client.asr.RealtimeSpeakerSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 实时 ASR 字幕显示与停止录音控制界面。
 *
 * <p>打开本页面即触发 recording.start；点击底部"结束录音"按钮（或返回键/返回
 * 按钮）触发 recording.stop，录音收尾完成后页面退出、返回主页。
 *
 * <p>页面不持有任何连接/会话对象，全部状态通过 {@link RecordingManager}
 * 订阅；录音过程中的 ASR 文本保存在 manager 中，页面重建不丢失。
 *
 * <p>VoiceAI 13.5 的返回结果以“近似时间 + 说话人 + 分段文本”展示。未落定文字
 * 和暂未确认的说话人使用浅色，随着云端结果收敛就地更新。
 */
public final class RealtimeRecordingActivity extends Activity {

    public static final String EXTRA_ATTACH_ONLY = "com.recorder.client.extra.ATTACH_ONLY";
    private static final int TIMER_TICK_MS = 200;

    /**
     * 暂停录音协议及状态机尚未实现，暂时隐藏该入口。
     * 实现暂停流程后改为 {@code true} 并绑定暂停行为，{@link #configurePauseControl(View)}
     * 会恢复暂停与停止按钮之间的原有布局。
     */
    private static final boolean SHOW_PAUSE_RECORDING_BUTTON = false;

    /** VoiceAI 尚未落定的文字颜色。 */
    private int colorTextPending;
    /** 说话人标签配色，按本录音中的首次出现顺序循环。 */
    private int[] speakerColors;

    private TextView tvTimer;
    private TextView tvStatus;
    private TextView tvAsrEmpty;
    private LinearLayout layoutAsrSegments;
    private RecordingWaveformView recordingWaveform;
    private View btnBack;
    private View btnStop;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RecordingManager manager;
    private boolean stopping;
    /** 分段结构未变时复用已有 View，只更新文本，避免每次中间结果都重新 inflate。 */
    private List<String> renderedBlockKeys = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_realtime_recording);

        // 状态栏取渐变衬片顶端混合色，导航栏与页面底色一致；API 26+ 原生支持。
        SystemBars.styleLight(getWindow(), ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarPageAlt), ThemeColorResolver.color(this,
                R.attr.recorderColorPageBackgroundAlt));
        colorTextPending = ThemeColorResolver.color(this, R.attr.recorderColorTextPending);
        speakerColors = ThemeColorResolver.colors(this,
                R.attr.recorderColorSpeakerRealtimeOne,
                R.attr.recorderColorSpeakerRealtimeTwo,
                R.attr.recorderColorSpeakerRealtimeThree,
                R.attr.recorderColorSpeakerRealtimeFour,
                R.attr.recorderColorSpeakerRealtimeFive);

        // 状态栏/导航栏遮挡适配：在 XML 内边距基础上叠加系统窗口 insets。
        View root = findViewById(R.id.recRoot);
        SystemBars.applyPadding(root, SystemBars.padding(root,
                SystemBars.EDGE_TOP | SystemBars.EDGE_BOTTOM));

        // 卡片背景 + 设计稿外阴影：计时卡强调（Y8/24/8%），转写卡标准（Y6/18/6%）
        findViewById(R.id.timerCard).setBackground(CardShadowDrawable.prominent(this, 20f));
        findViewById(R.id.asrCard).setBackground(CardShadowDrawable.standard(this, 20f));

        // 按钮外阴影（LayerDrawable 叠层：底层阴影 + 上层保留原 ripple 按压反馈）。
        btnBack = findViewById(R.id.btnBack);
        View btnPause = findViewById(R.id.btnPause);
        btnPause.setBackground(layeredShadow(
                new ShadowedDrawable(this, ThemeColorResolver.color(this,
                        R.attr.recorderColorSurface), true, 0f, 6f, 16f,
                        ThemeColorResolver.color(this, R.attr.recorderColorShadowNeutralLarge)),
                R.drawable.bg_rec_pause_button));
        btnStop = findViewById(R.id.btnStopRecording);
        btnStop.setBackground(layeredShadow(
                new ShadowedDrawable(this, ThemeColorResolver.color(this,
                        R.attr.recorderColorRecordingStop), false, 26f, 6f, 16f,
                        ThemeColorResolver.color(this,
                                R.attr.recorderColorShadowRecordingStop)),
                R.drawable.bg_rec_stop_button));
        configurePauseControl(btnPause);

        tvTimer = findViewById(R.id.tvTimer);
        tvStatus = findViewById(R.id.tvRecStatus);
        tvAsrEmpty = findViewById(R.id.tvRealtimeAsrEmpty);
        layoutAsrSegments = findViewById(R.id.layoutRealtimeAsrSegments);
        recordingWaveform = findViewById(R.id.recordingWaveform);

        // 返回按钮与系统返回键行为一致：录音中先走停止流程。
        btnBack.setOnClickListener(v -> onBackPressed());
        // 暂停按钮（R.id.btnPause）为设计占位：当前版本不支持录音中暂停，不绑定行为。
        btnStop.setOnClickListener(v -> onStopClicked());

        manager = RecordingManager.get();

        AppRecorderSession s = manager.session();
        if (s == null) {
            tvStatus.setText("当前无法录音（未连接）");
            btnStop.setEnabled(false);
            ui.postDelayed(this::finish, 1200);
        } else if (s.state() == AppRecorderSession.MainState.READY
                && !getIntent().getBooleanExtra(EXTRA_ATTACH_ONLY, false)) {
            if (!manager.canStartRecording()) {
                tvStatus.setText("当前无法开始录音（实时识别管线未就绪）");
                btnStop.setEnabled(false);
                ui.postDelayed(this::finish, 1200);
            } else {
                manager.startRecording();
            }
        }
        startTimer();
    }

    /** 阴影层在下、原按钮背景（含 ripple）在上，既加外阴影又保留按压反馈。 */
    private Drawable layeredShadow(Drawable shadow, int originalBgRes) {
        return new LayerDrawable(new Drawable[]{
                shadow, getResources().getDrawable(originalBgRes, getTheme())});
    }

    /**
     * 隐藏暂停入口时同步移除停止按钮的起始间距，使其占满原暂停按钮及二者间距。
     * 暂停功能可用后，仅需打开 {@link #SHOW_PAUSE_RECORDING_BUTTON} 即会保留 XML 原始间距。
     */
    private void configurePauseControl(View btnPause) {
        btnPause.setVisibility(SHOW_PAUSE_RECORDING_BUTTON ? View.VISIBLE : View.GONE);
        if (!SHOW_PAUSE_RECORDING_BUTTON) {
            ViewGroup.MarginLayoutParams stopLayoutParams =
                    (ViewGroup.MarginLayoutParams) btnStop.getLayoutParams();
            stopLayoutParams.setMarginStart(0);
            btnStop.setLayoutParams(stopLayoutParams);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.setUiListener(uiListener);
    }

    @Override
    protected void onPause() {
        recordingWaveform.setRecording(false);
        manager.clearUiListener(uiListener);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (stopping) {
            return;
        }
        AppRecorderSession s = manager.session();
        if (s != null && s.canStopRecording()) {
            onStopClicked();
            return;
        }
        super.onBackPressed();
    }

    // ==================== 停止流程 ====================

    private void onStopClicked() {
        if (stopping) {
            return;
        }
        enterStoppingState();
        manager.stopRecording();
    }

    /** 停止流程开始后，所有停止相关入口保持同一禁用状态。 */
    private void enterStoppingState() {
        stopping = true;
        recordingWaveform.setRecording(false);
        btnBack.setEnabled(false);
        btnBack.setAlpha(0.6f);
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.6f);
        tvStatus.setText("正在停止录音…");
    }

    // ==================== Manager 事件 ====================

    private final RecordingManager.UiListener uiListener =
            new RecordingManager.UiListener() {
                @Override
                public void onRecordingStateChanged(AppRecorderSession.MainState state) {
                    switch (state) {
                        case STARTING_RECORDING:
                            recordingWaveform.setRecording(true);
                            tvAsrEmpty.setText("等待语音识别结果…");
                            tvStatus.setText("正在启动录音…");
                            break;
                        case RECEIVING_LIVE_AUDIO:
                            recordingWaveform.setRecording(true);
                            tvStatus.setText("实时识别中");
                            break;
                        case STOPPING_RECORDING:
                            // stopped Event 会先把会话推进 STOPPING；立即锁定全部入口，
                            // 避免用户再次发送 stop Request 干扰本地 ASR 停止屏障。
                            enterStoppingState();
                            break;
                        default:
                            recordingWaveform.setRecording(false);
                            break;
                    }
                }

                @Override
                public void onAsrSegmentsChanged(List<RealtimeSpeakerSegment> segments) {
                    List<DisplayBlock> blocks = buildDisplayBlocks(segments);
                    if (blocks.isEmpty()) {
                        layoutAsrSegments.setVisibility(View.GONE);
                        tvAsrEmpty.setVisibility(View.VISIBLE);
                        layoutAsrSegments.removeAllViews();
                        renderedBlockKeys = Collections.emptyList();
                        return;
                    }
                    tvAsrEmpty.setVisibility(View.GONE);
                    layoutAsrSegments.setVisibility(View.VISIBLE);
                    renderBlocks(blocks);
                }

                @Override
                public void onRealtimeAsrError(String message) {
                    if (!stopping) {
                        tvStatus.setText("实时识别失败，录音仍在继续");
                    }
                    if (layoutAsrSegments.getVisibility() != View.VISIBLE) {
                        tvAsrEmpty.setText("实时识别暂不可用");
                        tvAsrEmpty.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onAudioLevelChanged(float level) {
                    recordingWaveform.setAudioLevel(level);
                }

                @Override
                public void onRecordingStopped(long frameCount, int receivedFrameCount) {
                    // VoiceAI 最终分段已收尾；退出页面返回主页。
                    finish();
                }

                @Override
                public void onSessionGone(String reason) {
                    finish();
                }
            };

    // ==================== 实时说话人分段 ====================

    /** 同一说话人的连续 SDK 分段合并为一个竞品式展示块。 */
    private List<DisplayBlock> buildDisplayBlocks(List<RealtimeSpeakerSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        List<DisplayBlock> blocks = new ArrayList<>();
        int lastConfirmedSpeaker = 0;
        for (RealtimeSpeakerSegment segment : segments) {
            if (segment == null || segment.text.trim().isEmpty()) {
                continue;
            }
            int effectiveSpeaker = segment.speakerNumber;
            if (effectiveSpeaker > 0) {
                lastConfirmedSpeaker = effectiveSpeaker;
            } else {
                // 云端初期 speakerId=-1 时先附着到上一位已确认说话人，确认后整块
                // 会自动重组；首句尚无人可附着时以“识别中”独立展示。
                effectiveSpeaker = lastConfirmedSpeaker;
            }
            DisplayBlock block = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (block == null || block.speakerNumber != effectiveSpeaker) {
                block = new DisplayBlock(effectiveSpeaker, segment.approximateStartMs);
                blocks.add(block);
            }
            block.add(segment);
        }
        return blocks;
    }

    private void renderBlocks(List<DisplayBlock> blocks) {
        List<String> nextKeys = new ArrayList<>(blocks.size());
        for (DisplayBlock block : blocks) {
            nextKeys.add(block.key());
        }
        boolean structureChanged = !nextKeys.equals(renderedBlockKeys)
                || layoutAsrSegments.getChildCount() != blocks.size();
        if (structureChanged) {
            layoutAsrSegments.removeAllViews();
            for (int i = 0; i < blocks.size(); i++) {
                View child = getLayoutInflater().inflate(R.layout.item_realtime_asr_segment,
                        layoutAsrSegments, false);
                layoutAsrSegments.addView(child);
            }
            renderedBlockKeys = nextKeys;
        }
        for (int i = 0; i < blocks.size(); i++) {
            bindBlock(layoutAsrSegments.getChildAt(i), blocks.get(i));
        }
    }

    private void bindBlock(View view, DisplayBlock block) {
        TextView time = view.findViewById(R.id.tvRealtimeSegmentTime);
        TextView speaker = view.findViewById(R.id.tvRealtimeSpeakerLabel);
        TextView text = view.findViewById(R.id.tvRealtimeSegmentText);
        time.setText(formatApproximateTime(block.approximateStartMs));
        int color = block.speakerNumber > 0
                ? speakerColors[(block.speakerNumber - 1) % speakerColors.length]
                : colorTextPending;
        speaker.setText(block.speakerNumber > 0
                ? "说话人 " + block.speakerNumber : "说话人识别中");
        speaker.setTextColor(color);
        text.setText(block.styledText());
    }

    private static String formatApproximateTime(long positionMs) {
        long seconds = Math.max(0L, positionMs / 1_000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private final class DisplayBlock {
        final int speakerNumber;
        final long approximateStartMs;
        final List<RealtimeSpeakerSegment> segments = new ArrayList<>();

        DisplayBlock(int speakerNumber, long approximateStartMs) {
            this.speakerNumber = speakerNumber;
            this.approximateStartMs = approximateStartMs;
        }

        void add(RealtimeSpeakerSegment segment) {
            segments.add(segment);
        }

        String key() {
            StringBuilder key = new StringBuilder().append(speakerNumber);
            for (RealtimeSpeakerSegment segment : segments) {
                key.append('|').append(segment.segmentId);
            }
            return key.toString();
        }

        CharSequence styledText() {
            StringBuilder content = new StringBuilder();
            List<int[]> pendingRanges = new ArrayList<>();
            for (RealtimeSpeakerSegment segment : segments) {
                int start = content.length();
                content.append(segment.text);
                if (!segment.completed || segment.speakerNumber == 0) {
                    pendingRanges.add(new int[]{start, content.length()});
                }
            }
            SpannableString styled = new SpannableString(content);
            for (int[] range : pendingRanges) {
                if (range[1] > range[0]) {
                    styled.setSpan(new ForegroundColorSpan(colorTextPending),
                            range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return styled;
        }
    }

    // ==================== 计时 ====================

    private void startTimer() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                long startedAt = manager.recordingStartedAtMs();
                if (startedAt > 0) {
                    long elapsedSec = (System.currentTimeMillis() - startedAt) / 1000;
                    tvTimer.setText(String.format(Locale.US, "%02d:%02d:%02d",
                            elapsedSec / 3600, (elapsedSec / 60) % 60, elapsedSec % 60));
                }
                ui.postDelayed(this, TIMER_TICK_MS);
            }
        }, TIMER_TICK_MS);
    }

}
