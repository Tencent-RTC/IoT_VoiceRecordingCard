package com.recorder.client;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 绑定设备页的雷达扫描波纹动画（设计稿 bind_device_scanning.png）。
 *
 * <p>使用 {@code recorderColorBrandLight} 主题 Token 绘制 3 层错峰、半透明的实心圆。每层从中心圆
 * 外沿向外扩散并渐隐，叠加后形成由内至外自然变浅的同心圆光晕；中心圆与扫描图标由布局
 * 叠加在本 View 之上。本 View 仅负责动画绘制，扫描结束调用 {@link #setScanning(boolean)}
 * 会停止并清空所有波纹。
 */
public final class RadarScanView extends View {

    private static final int RIPPLE_COUNT = 3;
    private static final long ANIM_DURATION_MS = 2_800L;
    /** 最内层光晕的最大不透明度；多层叠加后仍保持设计稿中的轻盈质感。 */
    private static final int RIPPLE_MAX_ALPHA = 50;
    private static final float CENTER_RADIUS_DP = 44f;
    /** 让最外层光晕保留呼吸空间，不紧贴雷达区域边界。 */
    private static final float OUTER_EDGE_INSET_DP = 8f;

    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 波纹从中心绿圆的外沿开始扩散。 */
    private final float centerRadius;
    private final float outerEdgeInset;
    private ValueAnimator animator;
    private float animFraction;
    private boolean scanning;

    public RadarScanView(Context context) {
        this(context, null);
    }

    public RadarScanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        centerRadius = CENTER_RADIUS_DP * density;
        outerEdgeInset = OUTER_EDGE_INSET_DP * density;
        ripplePaint.setColor(ThemeColorResolver.color(context,
                R.attr.recorderColorBrandLight));
        ripplePaint.setStyle(Paint.Style.FILL);
    }

    /** 开始 / 停止波纹动画。停止时波纹立即消失（扫描结束态为静态圆）。 */
    public void setScanning(boolean scanning) {
        if (this.scanning == scanning) {
            return;
        }
        this.scanning = scanning;
        if (scanning) {
            startAnimation();
        } else {
            stopAnimation();
            invalidate();
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    private void startAnimation() {
        stopAnimation();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            animFraction = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scanning) {
            return;
        }

        float maxRadius = Math.min(getWidth(), getHeight()) / 2f - outerEdgeInset;
        if (maxRadius <= centerRadius) {
            return;
        }

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        for (int index = RIPPLE_COUNT - 1; index >= 0; index--) {
            float phase = (animFraction + (float) index / RIPPLE_COUNT) % 1f;
            drawRipple(canvas, centerX, centerY, maxRadius, phase);
        }
    }

    private void drawRipple(
            Canvas canvas, float centerX, float centerY, float maxRadius, float phase) {
        float radius = centerRadius + easeOut(phase) * (maxRadius - centerRadius);
        ripplePaint.setAlpha(Math.round(RIPPLE_MAX_ALPHA * fadeOut(phase)));
        canvas.drawCircle(centerX, centerY, radius, ripplePaint);
    }

    /** 让波纹在靠近最外层时减速，稳定呈现设计稿中的同心圆层次。 */
    private static float easeOut(float fraction) {
        float remaining = 1f - fraction;
        return 1f - remaining * remaining;
    }

    /** 外圈淡出得更快，使远端光晕更轻、更接近设计稿。 */
    private static float fadeOut(float fraction) {
        float remaining = 1f - fraction;
        return remaining * remaining;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
