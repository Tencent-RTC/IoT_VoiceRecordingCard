package com.recorder.client;

import android.util.Log;

/**
 * Client App 的统一日志门面。
 *
 * <p>业务代码只能依赖本类，不得直接依赖 UI 或 {@code android.util.Log}。
 * 当前输出端为 Logcat；未来若需本地文件，只替换
 * {@link #emit(String, String, String, Throwable)} 的内部实现。
 */
public final class AppLog {

    private AppLog() {
    }

    public static void d(String tag, String message) {
        emit("D", tag, message, null);
    }

    public static void i(String tag, String message) {
        emit("I", tag, message, null);
    }

    public static void w(String tag, String message) {
        emit("W", tag, message, null);
    }

    public static void w(String tag, String message, Throwable throwable) {
        emit("W", tag, message, throwable);
    }

    public static void e(String tag, String message) {
        emit("E", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        emit("E", tag, message, throwable);
    }

    private static void emit(String level, String tag, String message, Throwable throwable) {
        String safeTag = tag == null || tag.isEmpty() ? "RecorderClient" : tag;
        // 加上统一前缀，便于在使用adb logcat看日志时予以区分。
        safeTag = "[AppLog]" + safeTag;
        String safeMessage = message == null ? "<null>" : message;
        switch (level) {
            case "D":
                if (throwable == null) {
                    Log.d(safeTag, safeMessage);
                } else {
                    Log.d(safeTag, safeMessage, throwable);
                }
                return;
            case "W":
                if (throwable == null) {
                    Log.w(safeTag, safeMessage);
                } else {
                    Log.w(safeTag, safeMessage, throwable);
                }
                return;
            case "E":
                if (throwable == null) {
                    Log.e(safeTag, safeMessage);
                } else {
                    Log.e(safeTag, safeMessage, throwable);
                }
                return;
            case "I":
            default:
                if (throwable == null) {
                    Log.i(safeTag, safeMessage);
                } else {
                    Log.i(safeTag, safeMessage, throwable);
                }
        }
    }
}
