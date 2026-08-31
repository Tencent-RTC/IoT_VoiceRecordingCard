package com.recorder.client;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.recorder.client.offline.RecordingHistoryStore;
import com.recorder.client.offline.RecordingNameFormatter;

/** 底部重命名面板，只管理输入与提交状态，不直接访问 SQLite。 */
public final class RecordingRenamePanel extends LinearLayout {

    public interface Listener {
        void onRenameConfirmed(RecordingHistoryStore.Entry entry, String recordingName);

        void onCanceled();
    }

    private EditText nameInput;
    private TextView confirm;
    private RecordingHistoryStore.Entry entry;
    private Listener listener;
    private boolean submitting;

    public RecordingRenamePanel(Context context) {
        this(context, null);
    }

    public RecordingRenamePanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecordingRenamePanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_recording_rename_panel, this, true);
        nameInput = findViewById(R.id.etRecordingName);
        confirm = findViewById(R.id.btnRenameConfirm);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        findViewById(R.id.btnRenameCancel).setOnClickListener(v -> cancel());
        confirm.setOnClickListener(v -> confirm());
    }

    public void bind(RecordingHistoryStore.Entry entry, String currentName, Listener listener) {
        this.entry = entry;
        this.listener = listener;
        setSubmitting(false);
        nameInput.setText(currentName);
        nameInput.setSelection(nameInput.length());
        nameInput.setError(null);
        post(() -> {
            nameInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public void setSubmitting(boolean submitting) {
        this.submitting = submitting;
        nameInput.setEnabled(!submitting);
        confirm.setEnabled(!submitting);
        confirm.setText(submitting ? "保存中…" : "确定");
    }

    public void dismissKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
        }
    }

    private void confirm() {
        if (submitting || entry == null || listener == null) {
            return;
        }
        String name = RecordingNameFormatter.normalizeUserName(nameInput.getText().toString());
        if (name == null) {
            nameInput.setError("请输入录音名称");
            nameInput.requestFocus();
            return;
        }
        setSubmitting(true);
        listener.onRenameConfirmed(entry, name);
    }

    private void cancel() {
        if (!submitting && listener != null) {
            listener.onCanceled();
        }
    }
}
