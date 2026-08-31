package com.recorder.client.transcription;

import com.recorder.client.cloudasr.AsrResult;
import com.recorder.client.cloudasr.AsrSegment;
import com.recorder.client.offline.RecordingHistoryStore;

import java.util.List;
import java.util.Locale;

/**
 * 将持久化的全量 ASR 结果转换为用户可阅读、可复制和可导出的文本。
 *
 * <p>详情页展示、剪切板和文本文件都使用同一套说话人和时间规则，避免把内部的
 * {@code asr_detail_json} 泄露给用户，或在不同出口产生不一致的文本。
 */
public final class TranscriptFormatter {

    private TranscriptFormatter() {
    }

    /** 只有云端全量转写成功且存在最终文本时，才允许用户复制或导出转写。 */
    public static boolean hasCompletedTranscript(RecordingHistoryStore.Entry entry) {
        return entry != null
                && entry.asrTextState
                == RecordingHistoryStore.AsrTextState.ASR_FULLY_BY_CLOUD
                && entry.asrText != null
                && !entry.asrText.trim().isEmpty()
                && entry.transcriptionState
                == RecordingHistoryStore.TranscriptionState.SUCCEEDED;
    }

    /**
     * 格式化一条已完成转写的记录。
     *
     * <p>优先使用结构化分段，输出格式为
     * {@code [00:10] 发言人 A：内容}，分段间以空行分隔。旧记录若没有可用分段，
     * 则退回持久化的纯文本，仍不会输出 JSON。
     */
    public static String format(RecordingHistoryStore.Entry entry) {
        if (entry == null) {
            return "";
        }
        return format(entry.asrText, AsrResult.segmentsFromJson(entry.asrDetailJson));
    }

    /** 供详情页和导出入口共用的格式化实现。 */
    public static String format(String plainText, List<AsrSegment> segments) {
        StringBuilder formatted = new StringBuilder();
        if (segments != null) {
            for (AsrSegment segment : segments) {
                if (segment == null || segment.text == null) {
                    continue;
                }
                String text = segment.text.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (formatted.length() > 0) {
                    formatted.append("\n\n");
                }
                int speaker = displaySpeaker(segment.speakerId);
                formatted.append('[')
                        .append(formatTimestamp(segment.startMs))
                        .append("] ")
                        .append(speakerName(speaker))
                        .append('：')
                        .append(text);
            }
        }
        if (formatted.length() > 0) {
            return formatted.toString();
        }
        return plainText == null ? "" : plainText.trim();
    }

    /** 服务端 SpeakerId 从 1 起；缺失值统一按第一位发言人展示。 */
    public static int displaySpeaker(int speakerId) {
        return Math.max(1, speakerId);
    }

    /** 与详情页一致：前 26 位发言人使用 A/B/C…，之后使用数字编号。 */
    public static String speakerName(int speaker) {
        int normalized = Math.max(1, speaker);
        return normalized <= 26
                ? "发言人 " + (char) ('A' + normalized - 1)
                : "发言人 " + normalized;
    }

    /** 将相对音频时间转为导出和详情页共用的 mm:ss 文案。 */
    public static String formatTimestamp(long positionMs) {
        long seconds = Math.max(0L, positionMs / 1_000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
}
