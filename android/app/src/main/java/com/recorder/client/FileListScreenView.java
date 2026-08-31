package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.recorder.client.offline.RecordingHistoryRepository;

/**
 * 历史录音文件列表页（底部导航"录音文件" Destination）。
 *
 * <p>按时间从晚到早罗列本地数据库中的全部历史录音记录，与主页面
 * "最近记录"共享同一份 {@link RecordingHistoryRepository}
 * 快照；用户长按任意条目可打开上下文菜单，删除操作仍受同步完成和转写状态保护。
 */
public final class FileListScreenView extends FrameLayout {

    private final RecordingListView fileList;
    private final TextView tvFilesEmpty;
    private Runnable onBackClickListener;

    public FileListScreenView(Context context) {
        this(context, null);
    }

    public FileListScreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.screen_files, this, true);
        fileList = findViewById(R.id.fileList);
        tvFilesEmpty = findViewById(R.id.tvFilesEmpty);
    }

    public void setOnHistoryEntryLongClickListener(
            RecordingListView.OnEntryLongClickListener listener) {
        fileList.setOnEntryLongClickListener(listener);
    }

    public void bindHistory(RecordingHistoryRepository.HistorySnapshot snapshot) {
        fileList.setSnapshot(snapshot);
        tvFilesEmpty.setVisibility(snapshot.entries.isEmpty() ? VISIBLE : GONE);
    }

    public void refreshHistory() {
        fileList.refresh();
    }
}
