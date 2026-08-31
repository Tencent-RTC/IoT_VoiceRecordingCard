package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;
import com.recorder.client.offline.RecordingNameFormatter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 一条历史录音的独立视图。
 *
 * <p>本类只负责单行的增量渲染、播放入口和长按意图；列表的快照调度由
 * {@link RecordingListView} 持有，重命名/删除菜单由页面宿主统一协调。这样单行不再
 * 耦合滑动手势、数据库操作或弹窗生命周期。
 */
public final class RecordingListItemView extends LinearLayout {

    public interface OnEntryLongClickListener {
        boolean onEntryLongClick(RecordingHistoryStore.Entry entry, View anchor);
    }

    private final int colorAccent;
    private final int colorPending;
    private final int colorTranscriptionFailed;

    private ImageView icon;
    private TextView title;
    private TextView recordedAt;
    private TextView duration;
    private TextView chip;
    private CircleProgressView progress;

    private OnEntryLongClickListener onEntryLongClickListener;
    private RecordingHistoryStore.Entry boundEntry;
    private int iconRes = -1;
    private CharSequence titleText;
    private CharSequence recordedAtText;
    private CharSequence durationText;
    private Boolean chipVisible;
    private int chipBgRes = -1;
    private CharSequence chipText;
    private int chipColor;
    private boolean progressVisible;
    private int progressPercentInt = -1;
    private RecordingHistoryStore.TransferState transferState;
    private String localPath;
    private boolean canPlay;

    public RecordingListItemView(Context context) {
        this(context, null);
    }

    public RecordingListItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecordingListItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 卡片背景和阴影由公共主题基建统一绘制。
        setBackground(CardShadowDrawable.standard(context, 20f));
        colorAccent = ThemeColorResolver.color(context, R.attr.recorderColorPrimary);
        colorPending = ThemeColorResolver.color(context, R.attr.recorderColorOnPrimary);
        colorTranscriptionFailed = ThemeColorResolver.color(context,
                R.attr.recorderColorStatusFailed);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        icon = findViewById(R.id.ivFileIcon);
        title = findViewById(R.id.tvFileTitle);
        recordedAt = findViewById(R.id.tvFileRecordedAt);
        duration = findViewById(R.id.tvFileDuration);
        chip = findViewById(R.id.tvStatusChip);
        progress = findViewById(R.id.progressCircle);
    }

    public void setOnEntryLongClickListener(OnEntryLongClickListener listener) {
        onEntryLongClickListener = listener;
        setLongClickable(listener != null);
    }

    /** 使用最新 Repository 快照就地渲染，逐 chunk 刷新时不重建视图树。 */
    public void bind(RecordingHistoryStore.Entry entry,
                     RecordingHistoryRepository.HistorySnapshot snapshot) {
        boundEntry = entry;
        String displayTitle = RecordingNameFormatter.displayName(entry.recordingName,
                entry.createdTimeSec);
        if (!displayTitle.equals(titleText)) {
            titleText = displayTitle;
            title.setText(displayTitle);
        }

        boolean activelyDownloading = snapshot.isActivelyDownloading(entry);
        double percent = entry.fileSize > 0L
                ? entry.transferOffset * 100.0 / entry.fileSize : 0.0;

        int nextIconRes;
        boolean nextChipVisible = true;
        int nextChipBgRes;
        String nextChipText;
        int nextChipColor;
        boolean nextProgressVisible = false;
        int nextProgressPercentInt = -1;

        switch (entry.transferState) {
            case TRANSMITTED:
                RecordingListStatusPolicy.DisplayState displayState = displayStateOf(entry);
                switch (displayState) {
                    case TRANSCRIBING:
                        nextIconRes = R.drawable.ic_file_done;
                        nextChipBgRes = R.drawable.bg_chip_done;
                        nextChipText = "转录中";
                        nextChipColor = colorAccent;
                        break;
                    case TRANSCRIBED:
                        nextIconRes = R.drawable.ic_file_done;
                        nextChipBgRes = R.drawable.bg_chip_done;
                        nextChipText = "已转录";
                        nextChipColor = colorAccent;
                        break;
                    case TRANSCRIPTION_FAILED:
                        nextIconRes = R.drawable.ic_file_transcription_failed;
                        nextChipBgRes = R.drawable.bg_chip_transcription_failed;
                        nextChipText = "转录失败";
                        nextChipColor = colorTranscriptionFailed;
                        break;
                    case DEVICE_CONFIRMING:
                    case COMPLETED:
                    default:
                        nextIconRes = R.drawable.ic_file_downloading;
                        nextChipBgRes = R.drawable.bg_chip_pending_transcription;
                        nextChipText = "待转录";
                        nextChipColor = colorPending;
                        break;
                }
                break;
            case TRANSMITTING:
                if (activelyDownloading) {
                    nextIconRes = R.drawable.ic_file_downloading;
                    nextChipVisible = false;
                    nextChipBgRes = 0;
                    nextChipText = null;
                    nextChipColor = 0;
                    nextProgressVisible = true;
                    nextProgressPercentInt = (int) (percent + 0.5);
                } else {
                    nextIconRes = R.drawable.ic_file_waiting_download;
                    nextChipBgRes = R.drawable.bg_chip_pending_import;
                    nextChipText = "待导入";
                    nextChipColor = colorPending;
                }
                break;
            case NOT_TRANSMITTED:
            default:
                nextIconRes = R.drawable.ic_file_waiting_download;
                nextChipBgRes = R.drawable.bg_chip_pending_import;
                nextChipText = "待导入";
                nextChipColor = colorPending;
                break;
        }

        if (nextIconRes != iconRes) {
            iconRes = nextIconRes;
            icon.setImageResource(nextIconRes);
        }
        String nextRecordedAt = recordingDateTimeText(entry.createdTimeSec);
        if (!nextRecordedAt.equals(recordedAtText)) {
            recordedAtText = nextRecordedAt;
            recordedAt.setText(nextRecordedAt);
        }
        String nextDuration = durationText(entry.durationMs);
        if (!nextDuration.equals(durationText)) {
            durationText = nextDuration;
            duration.setText(nextDuration);
        }
        if (chipVisible == null || nextChipVisible != chipVisible) {
            chipVisible = nextChipVisible;
            chip.setVisibility(nextChipVisible ? VISIBLE : GONE);
        }
        if (nextChipVisible) {
            if (nextChipBgRes != chipBgRes) {
                chipBgRes = nextChipBgRes;
                chip.setBackgroundResource(nextChipBgRes);
            }
            if (!nextChipText.equals(chipText)) {
                chipText = nextChipText;
                chip.setText(nextChipText);
            }
            if (nextChipColor != chipColor) {
                chipColor = nextChipColor;
                chip.setTextColor(nextChipColor);
            }
        }
        if (nextProgressVisible != progressVisible) {
            progressVisible = nextProgressVisible;
            progress.setVisibility(nextProgressVisible ? VISIBLE : GONE);
        }
        if (nextProgressVisible && nextProgressPercentInt != progressPercentInt) {
            progressPercentInt = nextProgressPercentInt;
            progress.setProgress(nextProgressPercentInt);
        }
        updateInteractions(entry, displayTitle);
    }

    private static RecordingListStatusPolicy.DisplayState displayStateOf(
            RecordingHistoryStore.Entry entry) {
        RecordingHistoryStore.TranscriptionState transcription = entry.transcriptionState;
        return RecordingListStatusPolicy.resolve(
                entry.transferState == RecordingHistoryStore.TransferState.TRANSMITTED,
                entry.deviceDeletionConfirmed,
                transcription.isActive(),
                transcription == RecordingHistoryStore.TranscriptionState.SUCCEEDED,
                transcription == RecordingHistoryStore.TranscriptionState.FAILED);
    }

    private void updateInteractions(RecordingHistoryStore.Entry entry, String displayTitle) {
        boolean signatureChanged = transferState != entry.transferState
                || (entry.localPath != null && !entry.localPath.equals(localPath))
                || (entry.localPath == null && localPath != null);
        transferState = entry.transferState;
        localPath = entry.localPath;
        boolean playable = false;
        if (entry.transferState == RecordingHistoryStore.TransferState.TRANSMITTED
                && entry.localPath != null) {
            if (signatureChanged) {
                canPlay = new File(entry.localPath).isFile();
            }
            playable = canPlay;
        } else {
            canPlay = false;
        }
        if (playable) {
            setOnClickListener(v -> RecordingDetailActivity.open(getContext(), entry.fileName,
                    new File(entry.localPath), displayTitle, entry.createdTimeSec,
                    entry.durationMs));
            setClickable(true);
        } else {
            setOnClickListener(null);
            setClickable(onEntryLongClickListener != null);
        }
        setOnLongClickListener(v -> {
            RecordingHistoryStore.Entry selected = boundEntry;
            return selected != null && onEntryLongClickListener != null
                    && onEntryLongClickListener.onEntryLongClick(selected, this);
        });
    }

    private static String recordingDateTimeText(long createdTimeSec) {
        if (createdTimeSec <= 0L) {
            return "-- --:--";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(createdTimeSec * 1_000L));
    }

    private static String durationText(long durationMs) {
        long wholeSeconds = Math.max(0L, durationMs / 1_000L);
        return String.format(Locale.getDefault(), "%02d:%02d",
                wholeSeconds / 60L, wholeSeconds % 60L);
    }
}
