package com.recorder.client;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 实时录音音波：以解码后的 PCM 响度驱动，并叠加轻微错峰呼吸，避免机械跳动。
 *
 * <p>仅使用 Canvas 与 {@link ValueAnimator}，最低兼容 API 11；本应用的 API 26 基线可直接使用。
 */
public final class RecordingWaveformView extends View {

    private static final long ANIMATION_DURATION_MS = 1_600L;
    private static final float[] BAR_HEIGHT_RATIOS = {
            10f / 44f, 22f / 44f, 34f / 44f, 16f / 44f, 28f / 44f, 12f / 44f,
            24f / 44f, 38f / 44f, 18f / 44f, 30f / 44f, 14f / 44f, 26f / 44f,
            20f / 44f, 8f / 44f
    };
    private static final float[] BAR_PHASES = {
            0.11f, 0.67f, 0.31f, 0.84f, 0.49f, 0.04f, 0.73f,
            0.38f, 0.92f, 0.56f, 0.19f, 0.79f, 0.44f, 0.98f
    };
    private static final int[] BAR_ALPHAS = {
            128, 179, 255, 153, 255, 128, 179, 255, 153, 255, 128, 179, 153, 102
    };
    private static final float TWO_PI = (float) (Math.PI * 2d);

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barBounds = new RectF();
    private final int primaryColor;
    private final int brandLightColor;
    private final float minBarHeight;

    private ValueAnimator animator;
    private boolean recording;
    private float audioLevel;
    private float animationFraction;

    public RecordingWaveformView(Context context) {
        this(context, null);
    }

    public RecordingWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        minBarHeight = 6f * density;
        primaryColor = ThemeColorResolver.color(context, R.attr.recorderColorPrimary);
        brandLightColor = ThemeColorResolver.color(context, R.attr.recorderColorBrandLight);
        barPaint.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 设置录音态；只有录音中才保持逐帧动画，离开页面或结束录音会立即停帧。 */
    public void setRecording(boolean recording) {
        if (this.recording == recording) {
            if (recording && animator == null && isAttachedToWindow()) {
                startAnimation();
            }
            return;
        }
        this.recording = recording;
        if (recording) {
            startAnimation();
        } else {
            stopAnimation();
        }
        invalidate();
    }

    /**
     * 设置经平滑处理后的声音强度，范围为 0..1；该方法应从主线程调用。
     */
    public void setAudioLevel(float audioLevel) {
        float clamped = Math.max(0f, Math.min(1f, audioLevel));
        if (Math.abs(this.audioLevel - clamped) < 0.005f) {
            return;
        }
        this.audioLevel = clamped;
        invalidate();
    }

    private void startAnimation() {
        if (!recording || animator != null) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIMATION_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            animationFraction = (float) animation.getAnimatedValue();
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
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0f || contentHeight <= 0f) {
            return;
        }

        float unit = contentWidth / (BAR_HEIGHT_RATIOS.length * 2f - 1f);
        float barWidth = unit;
        float centerY = getPaddingTop() + contentHeight / 2f;
        float maxBarHeight = Math.max(minBarHeight, contentHeight);
        for (int index = 0; index < BAR_HEIGHT_RATIOS.length; index++) {
            float baseHeight = Math.max(minBarHeight, maxBarHeight * BAR_HEIGHT_RATIOS[index]);
            float height = recording
                    ? animatedHeight(baseHeight, index)
                    : baseHeight;
            float left = getPaddingLeft() + index * unit * 2f;
            barBounds.set(left, centerY - height / 2f, left + barWidth, centerY + height / 2f);
            barPaint.setColor(BAR_ALPHAS[index] == 255 ? primaryColor : brandLightColor);
            barPaint.setAlpha(BAR_ALPHAS[index]);
            canvas.drawRoundRect(barBounds, barWidth / 2f, barWidth / 2f, barPaint);
        }
    }

    private float animatedHeight(float baseHeight, int index) {
        float phase = BAR_PHASES[index];
        float firstWave = (float) Math.sin(TWO_PI * (animationFraction * 0.95f + phase));
        float secondWave = (float) Math.sin(TWO_PI * (animationFraction * 1.71f + phase * 1.83f));
        float organicMotion = 0.5f + 0.5f * (firstWave * 0.68f + secondWave * 0.32f);
        // 真机正常说话时保留更高的基础高度：整体约抬升 15%~20%，峰值仍不越过原始设计稿高度。
        float scale = 0.45f + organicMotion * 0.08f
                + audioLevel * (0.38f + organicMotion * 0.32f);
        float height = minBarHeight + (baseHeight - minBarHeight) * Math.min(1f, scale);
        return Math.min(height, getHeight() - getPaddingTop() - getPaddingBottom());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (recording) {
            startAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
