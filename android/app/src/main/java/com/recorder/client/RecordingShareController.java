package com.recorder.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.recorder.client.offline.RecordingHistoryStore;
import com.recorder.client.offline.RecordingNameFormatter;
import com.recorder.client.transcription.TranscriptFormatter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 录音详情页分享面板的独立控制器。
 *
 * <p>本类统一管理面板显隐、转写门禁、剪切板、文本落盘和系统文件分享。详情页只需
 * 将最新历史记录传入 {@link #bindEntry(RecordingHistoryStore.Entry)}，不再关心分享
 * 的文件协议和异步状态。后续新增分享格式时也不需要继续扩张 Activity。
 */
public final class RecordingShareController {

    private static final String TAG = "RecordingShare";
    private static final String PROVIDER_AUTHORITY_SUFFIX =
            ".recording-share-fileprovider";

    private final Activity activity;
    private final File audioFile;
    private final String fileName;
    private final String initialDisplayTitle;
    private final int pageStatusBarColor;
    private final int scrimStatusBarColor;
    private final View overlay;
    private final View rowCopyTranscript;
    private final View rowExportRecording;
    private final View rowExportTranscript;
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "recording-share-export"));

    private RecordingHistoryStore.Entry currentEntry;
    private boolean exportingTranscript;
    private boolean destroyed;

    public RecordingShareController(Activity activity, File audioFile, String fileName,
                                    String initialDisplayTitle,
                                    int pageStatusBarColor, int scrimStatusBarColor) {
        this.activity = activity;
        this.audioFile = audioFile;
        this.fileName = fileName;
        this.initialDisplayTitle = initialDisplayTitle;
        this.pageStatusBarColor = pageStatusBarColor;
        this.scrimStatusBarColor = scrimStatusBarColor;
        overlay = activity.findViewById(R.id.shareOverlay);
        rowCopyTranscript = activity.findViewById(R.id.rowCopyTranscript);
        rowExportRecording = activity.findViewById(R.id.rowExportRecording);
        rowExportTranscript = activity.findViewById(R.id.rowExportTranscript);

        activity.findViewById(R.id.shareScrim).setOnClickListener(v -> dismiss());
        activity.findViewById(R.id.btnCloseSharePanel).setOnClickListener(v -> dismiss());
        rowCopyTranscript.setOnClickListener(v -> copyTranscriptToClipboard());
        rowExportRecording.setOnClickListener(v -> exportRecording());
        rowExportTranscript.setOnClickListener(v -> exportTranscript());
        updateActionAvailability();
    }

    /** Repository 每次发布这条录音的新快照时调用，打开中的面板也会立即更新门禁。 */
    public void bindEntry(RecordingHistoryStore.Entry entry) {
        currentEntry = entry;
        updateActionAvailability();
    }

    public void show() {
        if (destroyed) {
            return;
        }
        updateActionAvailability();
        overlay.setVisibility(View.VISIBLE);
        // View 蒙层只能覆盖状态栏下方；状态栏单独同步明度才能与设计稿连成一体。
        activity.getWindow().setStatusBarColor(scrimStatusBarColor);
    }

    public void dismiss() {
        if (overlay.getVisibility() != View.VISIBLE) {
            return;
        }
        overlay.setVisibility(View.GONE);
        activity.getWindow().setStatusBarColor(pageStatusBarColor);
    }

    public boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /** Activity 销毁时停止接收后台导出结果，避免持有失效的 Window。 */
    public void destroy() {
        destroyed = true;
        overlay.setVisibility(View.GONE);
        exportExecutor.shutdownNow();
    }

    private void updateActionAvailability() {
        boolean transcriptAvailable = TranscriptFormatter.hasCompletedTranscript(currentEntry);
        setActionEnabled(rowCopyTranscript, transcriptAvailable);
        setActionEnabled(rowExportTranscript, transcriptAvailable && !exportingTranscript);
        setActionEnabled(rowExportRecording, audioFile != null && audioFile.isFile());
    }

    /** 禁用时连同图标、文字和尾部操作整体置灰，并从点击与无障碍焦点中移除。 */
    private static void setActionEnabled(View action, boolean enabled) {
        action.setEnabled(enabled);
        action.setClickable(enabled);
        action.setFocusable(enabled);
        action.setAlpha(enabled ? 1.0f : 0.38f);
    }

    private String formattedCurrentTranscript() {
        if (!TranscriptFormatter.hasCompletedTranscript(currentEntry)) {
            return null;
        }
        String transcript = TranscriptFormatter.format(currentEntry);
        return transcript.trim().isEmpty() ? null : transcript;
    }

    private void copyTranscriptToClipboard() {
        String transcript = formattedCurrentTranscript();
        if (transcript == null) {
            updateActionAvailability();
            Toast.makeText(activity, "转写尚未完成", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                Activity.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(activity, "无法访问系统剪切板", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("录音转写", transcript));
        dismiss();
        Toast.makeText(activity, "转写已复制到剪切板", Toast.LENGTH_SHORT).show();
    }

    private void exportRecording() {
        if (audioFile == null || !audioFile.isFile()) {
            updateActionAvailability();
            Toast.makeText(activity, "本地录音文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        String displayName = shareBaseName() + ".ogg";
        dismiss();
        shareFile(audioFile, "audio/ogg", displayName, "分享录音");
    }

    private void exportTranscript() {
        if (exportingTranscript) {
            return;
        }
        String transcript = formattedCurrentTranscript();
        if (transcript == null) {
            updateActionAvailability();
            Toast.makeText(activity, "转写尚未完成", Toast.LENGTH_SHORT).show();
            return;
        }
        exportingTranscript = true;
        updateActionAvailability();
        String displayName = shareBaseName() + ".txt";
        exportExecutor.execute(() -> {
            try {
                File transcriptFile = writeTranscriptFile(transcript, displayName);
                activity.runOnUiThread(() -> onTranscriptFileReady(transcriptFile, displayName));
            } catch (IOException e) {
                activity.runOnUiThread(this::onTranscriptFileFailed);
            }
        });
    }

    private void onTranscriptFileReady(File transcriptFile, String displayName) {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        exportingTranscript = false;
        updateActionAvailability();
        dismiss();
        shareFile(transcriptFile, "text/plain", displayName, "分享转写");
    }

    private void onTranscriptFileFailed() {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        exportingTranscript = false;
        updateActionAvailability();
        Toast.makeText(activity, "导出转写文件失败", Toast.LENGTH_SHORT).show();
    }

    /**
     * 每条录音使用独立缓存子目录，文件叶子名称就是用户可读名称；即使接收方不查询
     * OpenableColumns.DISPLAY_NAME，而是退回 URI 最后一段，也不会再看到 UUID。
     */
    private File writeTranscriptFile(String transcript, String displayName) throws IOException {
        File root = new File(activity.getCacheDir(), "shared-transcripts");
        File directory = new File(root, safeStorageKey(fileName));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建转写导出目录");
        }
        File output = new File(directory, displayName);
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output), StandardCharsets.UTF_8))) {
            writer.write(transcript);
        }
        return output;
    }

    /**
     * FileProvider 的四参数重载会覆写 OpenableColumns.DISPLAY_NAME。系统 chooser 与
     * 真正的接收应用查询到的都是可读文件名，而不是音频在磁盘上的 UUID 名称。
     */
    private void shareFile(File file, String mimeType, String displayName, String chooserTitle) {
        if (!file.isFile()) {
            Toast.makeText(activity, "要分享的文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + PROVIDER_AUTHORITY_SUFFIX, file, displayName);
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "分享文件不在允许的 FileProvider 目录中：" + file, e);
            Toast.makeText(activity, "无法分享该文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, displayName)
                .putExtra(Intent.EXTRA_SUBJECT, displayName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(displayName, uri));
        Intent chooser = Intent.createChooser(send, chooserTitle)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "未找到可分享此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private String shareBaseName() {
        String title = currentEntry == null ? initialDisplayTitle
                : RecordingNameFormatter.displayName(currentEntry.recordingName,
                currentEntry.createdTimeSec);
        return sanitizeFileComponent(title, "录音", 48);
    }

    private static String safeStorageKey(String value) {
        return sanitizeFileComponent(value, "recording", 120);
    }

    /** 去除跨应用文件名中常见的非法分隔符，并限制长度以适配各厂商分享目标。 */
    private static String sanitizeFileComponent(String value, String fallback,
                                                int maxCodePoints) {
        String input = value == null ? "" : value.trim();
        StringBuilder safe = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            boolean forbidden = character < 0x20 || character == '\\' || character == '/'
                    || character == ':' || character == '*' || character == '?'
                    || character == '"' || character == '<' || character == '>'
                    || character == '|';
            safe.append(forbidden ? '_' : character);
        }
        String normalized = safe.toString().trim();
        while (normalized.endsWith(".") || normalized.endsWith(" ")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints > maxCodePoints) {
            normalized = normalized.substring(0,
                    normalized.offsetByCodePoints(0, maxCodePoints));
        }
        return normalized;
    }
}
