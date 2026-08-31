package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.recorder.client.offline.RecordingHistoryRepository;
import com.recorder.client.offline.RecordingHistoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 可在首页和文件页复用的历史录音列表。
 *
 * <p>组件只负责快照增量渲染与将长按意图上抛；每一项的展示逻辑由
 * {@link RecordingListItemView} 独立维护，菜单、重命名面板及持久化操作由 Activity
 * 作为跨页面浮层的宿主协调。
 */
public final class RecordingListView extends LinearLayout {

    public interface OnEntryLongClickListener {
        boolean onEntryLongClick(RecordingHistoryStore.Entry entry, View anchor);
    }

    private final LayoutInflater inflater;
    private final List<RecordingListItemView> rows = new ArrayList<>();
    private final List<String> renderedNames = new ArrayList<>();
    private int maxItems = Integer.MAX_VALUE;
    private RecordingHistoryRepository.HistorySnapshot historySnapshot;
    private OnEntryLongClickListener onEntryLongClickListener;

    public RecordingListView(Context context) {
        this(context, null);
    }

    public RecordingListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        // 卡片外阴影会溢出单行边界（见 CardShadowDrawable），不能被列表裁掉
        setClipChildren(false);
        inflater = LayoutInflater.from(context);
    }

    /** 限制渲染条数（首页“最近记录”只展示最近几条）。 */
    public void setMaxItems(int maxItems) {
        this.maxItems = Math.max(0, maxItems);
    }

    public void setOnEntryLongClickListener(OnEntryLongClickListener listener) {
        onEntryLongClickListener = listener;
        for (RecordingListItemView row : rows) {
            row.setOnEntryLongClickListener(this::dispatchEntryLongClick);
        }
    }

    /** 两个页面都通过该入口消费同一份不可变历史快照。 */
    public void setSnapshot(RecordingHistoryRepository.HistorySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        historySnapshot = snapshot;
        List<RecordingHistoryStore.Entry> entries = snapshot.entries;
        int count = Math.min(maxItems, entries.size());
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(entries.get(i).fileName);
        }
        if (!names.equals(renderedNames)) {
            rebuild(entries, count);
            return;
        }
        for (int i = 0; i < count; i++) {
            rows.get(i).bind(entries.get(i), snapshot);
        }
    }

    /** 按最近一次 Repository 快照就地重刷。 */
    public void refresh() {
        RecordingHistoryRepository.HistorySnapshot snapshot = historySnapshot;
        if (snapshot == null) {
            return;
        }
        int count = Math.min(maxItems, snapshot.entries.size());
        for (int i = 0; i < count && i < rows.size(); i++) {
            rows.get(i).bind(snapshot.entries.get(i), snapshot);
        }
    }

    /** 仅当条目增删或重排时才重建，逐 chunk 更新始终复用已有单行视图。 */
    private void rebuild(List<RecordingHistoryStore.Entry> entries, int count) {
        removeAllViews();
        rows.clear();
        renderedNames.clear();
        for (int i = 0; i < count; i++) {
            RecordingListItemView row = (RecordingListItemView) inflater.inflate(
                    R.layout.item_recording_file, this, false);
            row.setOnEntryLongClickListener(this::dispatchEntryLongClick);
            row.bind(entries.get(i), historySnapshot);
            rows.add(row);
            renderedNames.add(entries.get(i).fileName);
            addView(row);
        }
        // 阴影溢出区域不在单行脏矩形内，行集变化后让父布局整体重绘，避免边缘残留
        if (getParent() instanceof View) {
            ((View) getParent()).invalidate();
        }
    }

    private boolean dispatchEntryLongClick(RecordingHistoryStore.Entry entry, View anchor) {
        return onEntryLongClickListener != null
                && onEntryLongClickListener.onEntryLongClick(entry, anchor);
    }
}
