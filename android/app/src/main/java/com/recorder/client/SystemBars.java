package com.recorder.client;

import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 系统栏样式与 edge-to-edge 避让的公共入口。
 *
 * <p>Activity 只需声明由哪些 View 承接哪些方向的系统栏 inset。每个目标 View
 * 的 XML 初始 padding 会在注册监听时记录，后续 inset 多次分发也只会基于初始值
 * 计算，不会重复累加。监听返回原始 insets，允许未在此处声明的子 View 继续处理。
 */
public final class SystemBars {

    public static final int EDGE_LEFT = 1;
    public static final int EDGE_TOP = 1 << 1;
    public static final int EDGE_RIGHT = 1 << 2;
    public static final int EDGE_BOTTOM = 1 << 3;

    private SystemBars() {
    }

    /** 设置浅色系统栏背景及深色系统栏图标。 */
    public static void styleLight(Window window, int statusBarColor, int navigationBarColor) {
        window.setStatusBarColor(statusBarColor);
        window.setNavigationBarColor(navigationBarColor);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                window, window.getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }

    /**
     * 把系统栏与刘海区域的 inset 作为 padding 分发给各目标 View。
     *
     * @param dispatchView 接收 WindowInsets 的布局根节点
     * @param targets      一个或多个 padding 目标及其需要避让的方向
     */
    public static void applyPadding(View dispatchView, PaddingTarget... targets) {
        ViewCompat.setOnApplyWindowInsetsListener(dispatchView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            for (PaddingTarget target : targets) {
                target.apply(insets);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(dispatchView);
    }

    /** 创建一个以 View 当前 padding 为基线的 inset 目标。 */
    public static PaddingTarget padding(View view, int edges) {
        return new PaddingTarget(view, edges);
    }

    /** 一个 View 及其需要避让的物理方向。 */
    public static final class PaddingTarget {
        private final View view;
        private final int edges;
        private final int initialLeft;
        private final int initialTop;
        private final int initialRight;
        private final int initialBottom;

        private PaddingTarget(View view, int edges) {
            this.view = view;
            this.edges = edges;
            initialLeft = view.getPaddingLeft();
            initialTop = view.getPaddingTop();
            initialRight = view.getPaddingRight();
            initialBottom = view.getPaddingBottom();
        }

        private void apply(Insets insets) {
            view.setPadding(
                    initialLeft + insetFor(EDGE_LEFT, insets.left),
                    initialTop + insetFor(EDGE_TOP, insets.top),
                    initialRight + insetFor(EDGE_RIGHT, insets.right),
                    initialBottom + insetFor(EDGE_BOTTOM, insets.bottom));
        }

        private int insetFor(int edge, int inset) {
            return (edges & edge) != 0 ? inset : 0;
        }
    }
}
