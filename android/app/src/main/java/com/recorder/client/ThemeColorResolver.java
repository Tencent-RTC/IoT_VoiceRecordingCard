package com.recorder.client;

import android.content.Context;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

/**
 * 从当前 Context Theme 解析应用定义的颜色 Token。
 *
 * <p>自定义 View、Canvas 绘制和 Window 系统栏无法直接使用 XML Theme 属性时，
 * 必须通过此类获取颜色，不能在 Java 中保存具体色值。
 */
public final class ThemeColorResolver {

    private ThemeColorResolver() {
    }

    public static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            throw new IllegalArgumentException("Missing theme color attribute: " + attr);
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    public static int[] colors(Context context, int... attrs) {
        int[] colors = new int[attrs.length];
        for (int index = 0; index < attrs.length; index++) {
            colors[index] = color(context, attrs[index]);
        }
        return colors;
    }
}
