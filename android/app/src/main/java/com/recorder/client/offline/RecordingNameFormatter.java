package com.recorder.client.offline;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 录音展示名称的单一规则入口。
 *
 * <p>{@code file_name} 始终是不可变的业务身份；本类只处理可显示、可编辑的
 * {@code recording_name}。默认名称在记录首次写入 SQLite 时固化，之后绝不因
 * 设备列表刷新而覆盖用户重命名。
 */
public final class RecordingNameFormatter {

    private RecordingNameFormatter() {
    }

    /** 新同步录音的默认名称，例如“08月09日 13:52 录音记录”。 */
    public static String defaultName(long createdTimeSec) {
        if (createdTimeSec <= 0L) {
            return "录音记录";
        }
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
                .format(new Date(createdTimeSec * 1_000L)) + " 录音记录";
    }

    /** 将用户输入规整为可存储的名称；空白名称不合法。 */
    public static String normalizeUserName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** 防御性兜底，确保旧的异常行也始终有可展示的标题。 */
    public static String displayName(String recordingName, long createdTimeSec) {
        String normalized = normalizeUserName(recordingName);
        return normalized == null ? defaultName(createdTimeSec) : normalized;
    }
}
