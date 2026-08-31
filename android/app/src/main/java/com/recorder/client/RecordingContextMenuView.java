package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.recorder.client.offline.RecordingHistoryStore;

/** 长按录音后显示的就近操作菜单，仅派发重命名和删除意图。 */
public final class RecordingContextMenuView extends LinearLayout {

    public interface Listener {
        void onRenameRequested(RecordingHistoryStore.Entry entry);

        void onDeleteRequested(RecordingHistoryStore.Entry entry);
    }

    private RecordingHistoryStore.Entry entry;
    private Listener listener;

    public RecordingContextMenuView(Context context) {
        this(context, null);
    }

    public RecordingContextMenuView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecordingContextMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_recording_context_menu, this, true);
        // 浮层位于 interactionOverlay 顶层，阴影直接向边界外绘制，无需裁剪配合。
        setBackground(new ShadowedDrawable(context, ThemeColorResolver.color(context,
                R.attr.recorderColorSurface), false, 18f, 8f, 24f,
                ThemeColorResolver.color(context, R.attr.recorderColorShadowNeutralOverlay)));
        View rename = findViewById(R.id.btnContextRename);
        View delete = findViewById(R.id.btnContextDelete);
        rename.setOnClickListener(v -> {
            if (entry != null && listener != null) {
                listener.onRenameRequested(entry);
            }
        });
        delete.setOnClickListener(v -> {
            if (entry != null && listener != null) {
                listener.onDeleteRequested(entry);
            }
        });
    }

    public void bind(RecordingHistoryStore.Entry entry, Listener listener) {
        this.entry = entry;
        this.listener = listener;
    }
}
