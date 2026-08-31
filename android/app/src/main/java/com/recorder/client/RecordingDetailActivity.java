package com.recorder.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.recorder.client.cloudasr.AsrResult;
import com.recorder.client.cloudasr.AsrSegment;
import com.recorder.client.transcription.CloudTranscriptionController;
import com.recorder.client.transcription.TranscriptFormatter;
import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;
import com.recorder.client.offline.RecordingNameFormatter;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条已同步离线录音的详情页。
 *
 * <p>页面独占 {@link MediaPlayer} 的创建、播放控制和释放，避免主页历史列表持有
 * 播放状态。中部区域订阅 {@link RecordingHistoryRepository} 的单条录音状态：
 * 无结果时提供「转写」入口，转写期间显示循环 loading，失败时提供重试，完成后按
 * 「说话人头像 + 发言人·起始时间」分段展示识别文本。长时任务由应用级 Controller
 * 与 Worker 执行，退出本 Activity 不会取消转写。
 *
 * <p>UI 依据设计稿 ui_design/screens/recording_details.png 实现（AI 摘要区域
 * 暂不支持已省略）。右上角分享入口可复制格式化转写，或通过系统分享面板导出
 * 本地录音与转写文本；右下角更多按钮仍为设计占位。
 */
public final class RecordingDetailActivity extends Activity {

    private static final String EXTRA_AUDIO_PATH = "audio_path";
    private static final String EXTRA_RECORDING_TITLE = "recording_title";
    private static final String EXTRA_CREATED_TIME_SEC = "created_time_sec";
    private static final String EXTRA_DURATION_MS = "duration_ms";
    private static final String EXTRA_FILE_NAME = "file_name";
    private static final int PROGRESS_REFRESH_MS = 200;

    /** 分享面板关闭后恢复的状态栏颜色。 */
    private int colorPageStatusBar;
    /** 分享面板显示时使用的状态栏遮罩颜色。 */
    private int colorShareScrimStatusBar;
    /** 说话人头像/名称配色，按发言人序号循环取用。 */
    private int[] speakerColors;

    private TextView tvProgress;
    private TextView tvTitle;
    private TextView tvDuration;
    private SeekBar sbPlayback;
    private ImageButton btnTogglePlayback;
    private View btnPlaybackSpeed;
    private TextView tvPlaybackSpeed;
    private TextView tvRecordedTime;

    private View groupTranscribeEmpty;
    private View groupTranscribeLoading;
    private View groupTranscribeResult;
    private TextView tvTranscriptionEmptyHint;
    private TextView tvAsrResult;
    private LinearLayout layoutAsrSegments;
    private Button btnTranscribe;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgressText();
            ui.postDelayed(this, PROGRESS_REFRESH_MS);
        }
    };

    private File audioFile;
    private String fileName;
    private long declaredDurationMs;
    private MediaPlayer mediaPlayer;
    private boolean prepared;
    private boolean preparing;
    private float playbackSpeed = 1.0f;
    /** 迷你播放按钮请求的起播点；异步 prepare 完成后消费。-1 表示无待定点播。 */
    private long pendingSeekMs = -1L;
    /** 进度条拖动中暂停进度回填，避免与用户拖拽打架。 */
    private boolean seekDragging;
    /** 副标题固定部分（录制时间 · 时长）；转写完成后追加发言人数。 */
    private String baseSubtitle = "";

    private RecordingHistoryRepository historyRepository;
    private CloudTranscriptionController cloudTranscription;
    private boolean transcriptionCommandPending;
    private RecordingShareController shareController;

    /** 页面只订阅单条 Repository 状态；Worker 完全不知道 Activity 是否存在。 */
    private final RecordingHistoryRepository.EntryListener historyEntryListener =
            this::renderTranscriptionEntry;

    /** 创建详情页的唯一入口，保持首页只负责导航，不再参与播放。 */
    public static void open(Context context, String fileName, File audioFile,
                            String recordingTitle, long createdTimeSec, long durationMs) {
        Intent intent = new Intent(context, RecordingDetailActivity.class);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_AUDIO_PATH, audioFile.getAbsolutePath());
        intent.putExtra(EXTRA_RECORDING_TITLE, recordingTitle);
        intent.putExtra(EXTRA_CREATED_TIME_SEC, createdTimeSec);
        intent.putExtra(EXTRA_DURATION_MS, durationMs);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording_detail);

        colorPageStatusBar = ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarPageAlt);
        colorShareScrimStatusBar = ThemeColorResolver.color(this,
                R.attr.recorderColorSystemBarScrim);
        speakerColors = ThemeColorResolver.colors(this,
                R.attr.recorderColorSpeakerDetailOne,
                R.attr.recorderColorSpeakerDetailTwo,
                R.attr.recorderColorSpeakerDetailThree,
                R.attr.recorderColorSpeakerDetailFour);

        // 状态栏取渐变衬片顶端混合色 + 浅色导航栏（底部播放面板白色）；API 26+ 原生支持。
        SystemBars.styleLight(getWindow(), colorPageStatusBar, ThemeColorResolver.color(this,
                R.attr.recorderColorSurface));

        // 状态栏/导航栏遮挡适配：顶部 inset 仅加在详情内容，分享蒙层仍需覆盖整窗；
        // 两个白色底部面板均延伸到导航栏区域，中间不留灰条。
        View root = findViewById(R.id.detailRoot);
        View content = findViewById(R.id.detailContent);
        View sheet = findViewById(R.id.playerSheet);
        View sharePanel = findViewById(R.id.sharePanel);
        SystemBars.applyPadding(root,
                SystemBars.padding(content, SystemBars.EDGE_TOP),
                SystemBars.padding(sheet, SystemBars.EDGE_BOTTOM),
                SystemBars.padding(sharePanel, SystemBars.EDGE_BOTTOM));

        tvTitle = findViewById(R.id.tvRecordingDetailTitle);
        tvRecordedTime = findViewById(R.id.tvRecordingDetailTime);
        tvProgress = findViewById(R.id.tvPlaybackProgress);
        tvDuration = findViewById(R.id.tvPlaybackDuration);
        sbPlayback = findViewById(R.id.sbPlayback);
        btnTogglePlayback = findViewById(R.id.btnTogglePlayback);
        btnPlaybackSpeed = findViewById(R.id.btnPlaybackSpeed);
        tvPlaybackSpeed = findViewById(R.id.tvPlaybackSpeed);
        groupTranscribeEmpty = findViewById(R.id.groupTranscribeEmpty);
        groupTranscribeLoading = findViewById(R.id.groupTranscribeLoading);
        groupTranscribeResult = findViewById(R.id.groupTranscribeResult);
        tvTranscriptionEmptyHint = findViewById(R.id.tvTranscriptionEmptyHint);
        tvAsrResult = findViewById(R.id.tvAsrResult);
        layoutAsrSegments = findViewById(R.id.layoutAsrSegments);
        btnTranscribe = findViewById(R.id.btnTranscribe);

        String audioPath = getIntent().getStringExtra(EXTRA_AUDIO_PATH);
        audioFile = audioPath == null ? null : new File(audioPath);
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        declaredDurationMs = Math.max(0L, getIntent().getLongExtra(EXTRA_DURATION_MS, 0L));
        if (audioFile == null || !audioFile.isFile()) {
            Toast.makeText(this, "本地录音文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        long createdTimeSec = getIntent().getLongExtra(EXTRA_CREATED_TIME_SEC, 0L);
        String recordingTitle = getIntent().getStringExtra(EXTRA_RECORDING_TITLE);
        tvTitle.setText(RecordingNameFormatter.displayName(recordingTitle, createdTimeSec));
        refreshSubtitle(createdTimeSec);
        tvDuration.setText(formatPlaybackTime(declaredDurationMs));
        shareController = new RecordingShareController(this, audioFile, fileName,
                tvTitle.getText().toString(),
                colorPageStatusBar, colorShareScrimStatusBar);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareController.show());
        // btnMore 为设计占位：当前版本暂不支持更多操作。
        btnTogglePlayback.setOnClickListener(v -> togglePlayback());
        findViewById(R.id.btnRewindFiveSeconds).setOnClickListener(v -> seekBy(-5_000));
        findViewById(R.id.btnForwardFiveSeconds).setOnClickListener(v -> seekBy(5_000));
        btnPlaybackSpeed.setOnClickListener(this::showPlaybackSpeedMenu);
        btnTranscribe.setOnClickListener(v -> startCloudTranscribe());
        sbPlayback.setOnSeekBarChangeListener(seekBarListener);
        RecordingManager manager = RecordingManager.get();
        manager.initBackground(this);
        historyRepository = manager.historyRepository();
        cloudTranscription = manager.cloudTranscription();
        renderCenterState(CenterState.EMPTY);
        historyRepository.addEntryListener(fileName, historyEntryListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(progressUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(progressUpdater);
        pausePlayback();
    }

    @Override
    public void onBackPressed() {
        if (shareController != null && shareController.isShowing()) {
            shareController.dismiss();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(progressUpdater);
        if (shareController != null) {
            shareController.destroy();
        }
        releasePlayer();
        if (historyRepository != null) {
            historyRepository.removeEntryListener(fileName, historyEntryListener);
        }
    }

    // ==================== 云端全量 ASR ====================

    /** 中部区域四态：无内容 / 转写中 / 失败重试 / 展示结果。 */
    private enum CenterState {
        EMPTY,
        ERROR,
        LOADING,
        RESULT
    }

    private void renderCenterState(CenterState state) {
        groupTranscribeEmpty.setVisibility(
                state == CenterState.EMPTY || state == CenterState.ERROR
                        ? View.VISIBLE : View.GONE);
        groupTranscribeLoading.setVisibility(
                state == CenterState.LOADING ? View.VISIBLE : View.GONE);
        groupTranscribeResult.setVisibility(
                state == CenterState.RESULT ? View.VISIBLE : View.GONE);
    }

    private void renderTranscriptionEntry(RecordingHistoryStore.Entry entry) {
        if (isDestroyed() || entry == null) {
            return;
        }
        if (shareController != null) {
            shareController.bindEntry(entry);
        }
        tvTitle.setText(RecordingNameFormatter.displayName(entry.recordingName,
                entry.createdTimeSec));
        transcriptionCommandPending = false;
        if (entry.asrTextState == RecordingHistoryStore.AsrTextState.ASR_FULLY_BY_CLOUD
                && entry.asrText != null && !entry.asrText.isEmpty()) {
            showAsrResult(entry.asrText, AsrResult.segmentsFromJson(entry.asrDetailJson));
            return;
        }
        if (entry.transcriptionState.isActive()) {
            renderCenterState(CenterState.LOADING);
            return;
        }
        if (entry.transcriptionState == RecordingHistoryStore.TranscriptionState.FAILED) {
            String reason = entry.transcriptionError == null
                    || entry.transcriptionError.isEmpty()
                    ? "转写失败，请重试" : "转写失败：" + entry.transcriptionError;
            tvTranscriptionEmptyHint.setText(reason);
            btnTranscribe.setText("重新转写");
            renderCenterState(CenterState.ERROR);
            return;
        }
        tvTranscriptionEmptyHint.setText("暂无转写内容");
        btnTranscribe.setText("转写");
        renderCenterState(CenterState.EMPTY);
    }

    private void startCloudTranscribe() {
        if (transcriptionCommandPending || audioFile == null || cloudTranscription == null) {
            return;
        }
        transcriptionCommandPending = true;
        renderCenterState(CenterState.LOADING);
        cloudTranscription.enqueue(fileName, audioFile,
                new CloudTranscriptionController.CommandCallback() {
                    @Override
                    public void onAccepted() {
                        transcriptionCommandPending = false;
                    }

                    @Override
                    public void onRejected(String reason) {
                        transcriptionCommandPending = false;
                        if (!isDestroyed()) {
                            tvTranscriptionEmptyHint.setText("无法发起转写：" + reason);
                            btnTranscribe.setText("重试");
                            renderCenterState(CenterState.ERROR);
                            Toast.makeText(RecordingDetailActivity.this,
                                    "无法发起转写：" + reason, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void showAsrResult(String text, List<AsrSegment> segments) {
        if (segments != null && !segments.isEmpty()) {
            tvAsrResult.setVisibility(View.GONE);
            layoutAsrSegments.setVisibility(View.VISIBLE);
            renderSegmentBlocks(segments);
            // 设计稿副标题含发言人数：转写结果可用后追加展示。
            Set<Integer> speakers = new HashSet<>();
            for (AsrSegment segment : segments) {
                speakers.add(TranscriptFormatter.displaySpeaker(segment.speakerId));
            }
            tvRecordedTime.setText(baseSubtitle + " · " + speakers.size() + "位发言人");
        } else {
            // 无说话人分段（旧数据或服务端未返回详情）时退化为纯文本展示。
            layoutAsrSegments.setVisibility(View.GONE);
            tvAsrResult.setVisibility(View.VISIBLE);
            tvAsrResult.setText(text);
        }
        renderCenterState(CenterState.RESULT);
    }

    /**
     * 按「连续同说话人合并为一块」渲染分段视图：头部行为圆形头像 + 发言人名
     * （与头像同色）+ 该块起始时间（mm:ss），点击头部从该块起播；块文本紧随其下。
     */
    private void renderSegmentBlocks(List<AsrSegment> segments) {
        layoutAsrSegments.removeAllViews();
        int index = 0;
        while (index < segments.size()) {
            int speaker = TranscriptFormatter.displaySpeaker(segments.get(index).speakerId);
            long blockStartMs = segments.get(index).startMs;
            StringBuilder blockText = new StringBuilder();
            while (index < segments.size()
                    && TranscriptFormatter.displaySpeaker(segments.get(index).speakerId)
                    == speaker) {
                blockText.append(segments.get(index).text);
                index++;
            }

            View block = getLayoutInflater().inflate(R.layout.item_asr_segment,
                    layoutAsrSegments, false);
            View row = block.findViewById(R.id.rowSpeaker);
            ImageView avatar = block.findViewById(R.id.ivSpeakerAvatar);
            TextView speakerLabel = block.findViewById(R.id.tvSpeakerLabel);
            TextView timeLabel = block.findViewById(R.id.tvSegmentTime);
            TextView textView = block.findViewById(R.id.tvSegmentText);
            int color = speakerColor(speaker);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(color);
            avatar.setBackground(circle);
            speakerLabel.setText(TranscriptFormatter.speakerName(speaker));
            speakerLabel.setTextColor(color);
            timeLabel.setText("· " + TranscriptFormatter.formatTimestamp(blockStartMs));
            textView.setText(blockText.toString());
            row.setOnClickListener(v -> playFromMs(blockStartMs));
            layoutAsrSegments.addView(block);
        }
    }

    private int speakerColor(int speaker) {
        return speakerColors[(Math.max(1, speaker) - 1) % speakerColors.length];
    }

    /**
     * 迷你播放按钮：跳转到该讲话人分段的起始位置并起播。播放器尚未创建时
     * 记录待起播点，待异步 prepare 完成后消费。
     */
    private void playFromMs(long positionMs) {
        if (mediaPlayer != null && prepared) {
            int target = (int) Math.max(0L, Math.min(positionMs, mediaPlayer.getDuration()));
            mediaPlayer.seekTo(target);
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }
            renderPlaybackButton();
            updateProgressText();
            return;
        }
        pendingSeekMs = positionMs;
        if (mediaPlayer == null && !preparing) {
            prepareAndStartPlayback();
        }
    }

    private void togglePlayback() {
        if (preparing) {
            return;
        }
        if (mediaPlayer == null) {
            prepareAndStartPlayback();
        } else if (prepared && mediaPlayer.isPlaying()) {
            pausePlayback();
        } else if (prepared) {
            mediaPlayer.start();
            renderPlaybackButton();
        }
    }

    private void prepareAndStartPlayback() {
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(audioFile.getAbsolutePath());
            player.setOnPreparedListener(preparedPlayer -> {
                if (mediaPlayer != preparedPlayer) {
                    preparedPlayer.release();
                    return;
                }
                prepared = true;
                preparing = false;
                applyPlaybackSpeed(preparedPlayer);
                int actualDuration = preparedPlayer.getDuration();
                if (actualDuration > 0) {
                    declaredDurationMs = actualDuration;
                    // 设备声明时长与实际时长可能不一致：以实际时长刷新副标题。
                    refreshSubtitle(getIntent().getLongExtra(EXTRA_CREATED_TIME_SEC, 0L));
                }
                // 迷你播放按钮在 prepare 完成前点击时，按记录的起播点先跳转。
                if (pendingSeekMs >= 0L) {
                    int target = (int) Math.max(0L, Math.min(pendingSeekMs,
                            declaredDurationMs));
                    preparedPlayer.seekTo(target);
                    pendingSeekMs = -1L;
                }
                preparedPlayer.start();
                renderPlaybackButton();
                updateProgressText();
            });
            player.setOnCompletionListener(completedPlayer -> {
                if (mediaPlayer == completedPlayer) {
                    releasePlayer();
                    tvProgress.setText("0:00");
                    renderPlaybackButton();
                } else {
                    completedPlayer.release();
                }
            });
            player.setOnErrorListener((errorPlayer, what, extra) -> {
                if (mediaPlayer == errorPlayer) {
                    releasePlayer();
                    renderPlaybackButton();
                } else {
                    errorPlayer.release();
                }
                Toast.makeText(this, "无法播放该本地录音", Toast.LENGTH_SHORT).show();
                return true;
            });
            mediaPlayer = player;
            preparing = true;
            renderPlaybackButton();
            player.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            player.release();
            if (mediaPlayer == player) {
                mediaPlayer = null;
            }
            preparing = false;
            renderPlaybackButton();
            Toast.makeText(this, "打开本地录音失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void pausePlayback() {
        if (mediaPlayer != null && prepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            renderPlaybackButton();
        }
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || !prepared) {
            return;
        }
        long durationMs = Math.max(0, mediaPlayer.getDuration());
        long targetMs = Math.max(0L, Math.min(durationMs,
                (long) mediaPlayer.getCurrentPosition() + deltaMs));
        mediaPlayer.seekTo((int) targetMs);
        updateProgressText();
    }

    private void showPlaybackSpeedMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("1.0x");
        menu.getMenu().add("1.25x");
        menu.getMenu().add("1.5x");
        menu.getMenu().add("2.0x");
        menu.setOnMenuItemClickListener(this::onPlaybackSpeedSelected);
        menu.show();
    }

    private boolean onPlaybackSpeedSelected(MenuItem item) {
        String label = item.getTitle().toString();
        playbackSpeed = Float.parseFloat(label.substring(0, label.length() - 1));
        tvPlaybackSpeed.setText(label);
        if (mediaPlayer != null && prepared) {
            applyPlaybackSpeed(mediaPlayer);
        }
        return true;
    }

    private void applyPlaybackSpeed(MediaPlayer player) {
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(playbackSpeed));
        } catch (IllegalStateException ignored) {
            // 异步 prepare 完成与 Activity 销毁交错时，播放器可能已释放。
        }
    }

    private void updateProgressText() {
        long progressMs = 0L;
        long durationMs = declaredDurationMs;
        MediaPlayer player = mediaPlayer;
        if (player != null && prepared) {
            try {
                progressMs = Math.max(0, player.getCurrentPosition());
                int actualDuration = player.getDuration();
                if (actualDuration > 0) {
                    durationMs = actualDuration;
                    declaredDurationMs = actualDuration;
                }
            } catch (IllegalStateException ignored) {
                // release 期间保持上一次的文本即可。
            }
        }
        tvProgress.setText(formatPlaybackTime(progressMs));
        tvDuration.setText(formatPlaybackTime(durationMs));
        if (!seekDragging) {
            sbPlayback.setProgress(durationMs > 0L
                    ? (int) (progressMs * 1_000L / durationMs) : 0);
        }
    }

    // ==================== 进度条拖动 ====================

    private final SeekBar.OnSeekBarChangeListener seekBarListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        // 拖动过程中实时预览目标时间点。
                        tvProgress.setText(formatPlaybackTime(
                                currentDurationMs() * progress / 1_000L));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    seekDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seekDragging = false;
                    long durationMs = currentDurationMs();
                    if (durationMs > 0L) {
                        // 播放器未就绪时 playFromMs 会记录起播点，prepare 完成后消费。
                        playFromMs(seekBar.getProgress() * durationMs / 1_000L);
                    }
                }
            };

    private long currentDurationMs() {
        MediaPlayer player = mediaPlayer;
        if (player != null && prepared) {
            try {
                int actualDuration = player.getDuration();
                if (actualDuration > 0) {
                    return actualDuration;
                }
            } catch (IllegalStateException ignored) {
                // release 期间退回声明时长。
            }
        }
        return declaredDurationMs;
    }

    private void renderPlaybackButton() {
        boolean playing = mediaPlayer != null && prepared && mediaPlayer.isPlaying();
        btnTogglePlayback.setEnabled(!preparing);
        btnTogglePlayback.setImageResource(
                playing ? R.drawable.ic_detail_pause : R.drawable.ic_detail_play);
        btnTogglePlayback.setContentDescription(playing ? "暂停" : "播放");
    }

    private void releasePlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        prepared = false;
        preparing = false;
        pendingSeekMs = -1L;
        if (player != null) {
            player.release();
        }
    }

    private static String formatPlaybackTime(long durationMs) {
        long seconds = Math.max(0L, durationMs / 1_000L);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    // ==================== 标题与副标题 ====================

    /** 副标题固定部分：录制时间（今天/昨天/MM月dd日 HH:mm）· 时长。 */
    private void refreshSubtitle(long createdTimeSec) {
        String dayTime = createdTimeSec > 0L
                ? relativeDay(createdTimeSec) + " "
                + new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(createdTimeSec * 1000L))
                : "未知录制时间";
        baseSubtitle = dayTime + " · " + formatDurationText(declaredDurationMs);
        tvRecordedTime.setText(baseSubtitle);
    }

    private static String relativeDay(long createdTimeSec) {
        Calendar today = Calendar.getInstance();
        Calendar thatDay = Calendar.getInstance();
        thatDay.setTimeInMillis(createdTimeSec * 1000L);
        int dayDiff = today.get(Calendar.DAY_OF_YEAR) - thatDay.get(Calendar.DAY_OF_YEAR);
        int yearDiff = today.get(Calendar.YEAR) - thatDay.get(Calendar.YEAR);
        if (yearDiff == 0 && dayDiff == 0) {
            return "今天";
        }
        if ((yearDiff == 0 && dayDiff == 1)
                || (yearDiff == 1 && today.get(Calendar.DAY_OF_YEAR) == 1
                && thatDay.get(Calendar.DAY_OF_YEAR)
                == thatDay.getActualMaximum(Calendar.DAY_OF_YEAR))) {
            return "昨天";
        }
        return new SimpleDateFormat("MM月dd日", Locale.getDefault())
                .format(new Date(createdTimeSec * 1000L));
    }

    /** 设计稿副标题时长文案：不足 1 分钟按秒，否则按分钟（四舍五入）。 */
    private static String formatDurationText(long durationMs) {
        long totalSec = Math.max(0L, Math.round(durationMs / 1_000.0));
        if (totalSec < 60L) {
            return totalSec + "秒";
        }
        return Math.round(totalSec / 60.0) + "分钟";
    }

}
