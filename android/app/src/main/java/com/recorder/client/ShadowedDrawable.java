package com.recorder.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * 公共基建：纯色填充形状（圆角矩形 / 椭圆）+ 设计稿外阴影。
 *
 * <p>阴影参数与设计稿「外阴影」面板一一对应：X=0、Y 偏移、模糊值、扩散=0、
 * 颜色 + 不透明度。形状支持圆角矩形（胶囊是其特例）与椭圆（正圆是其特例）。
 *
 * <p>BlurMaskFilter 在硬件加速画布上会被忽略，因此阴影在首次使用时一次性
 * 渲染进离屏位图（位图画布为软件渲染，模糊正常生效），之后每次 draw 只绘制
 * 位图与填充形状，滚动和高频刷新没有额外开销。位图按
 * 「尺寸 + 形状 + 圆角 + 阴影参数」全局共享，同规格同尺寸元素复用同一份。
 *
 * <p>阴影会向视图边界外溢出（Y 偏移 + 模糊扩散），依赖父布局
 * clipChildren=false 配合。
 */
public class ShadowedDrawable extends Drawable {

    private static final Map<String, Bitmap> SHADOW_CACHE = new HashMap<>();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final boolean oval;
    private final float cornerRadius;
    private final float shadowOffsetY;
    private final float shadowBlurRadius;
    private final int shadowColor;
    private final int shadowPad;
    private final RectF fillRect = new RectF();

    /**
     * @param fillColor      形状填充色
     * @param oval           true 画椭圆（视图正方形时即正圆），false 画圆角矩形
     * @param cornerRadiusDp 圆角半径（oval 时忽略，传 0）
     * @param offsetYDp      阴影 Y 偏移（设计稿「位置 Y」）
     * @param blurDp         阴影模糊值（设计稿「模糊值」全宽）
     * @param shadowColor    阴影颜色（含不透明度，由对应的 Theme Token 提供）
     */
    public ShadowedDrawable(Context context, int fillColor, boolean oval,
                            float cornerRadiusDp, float offsetYDp, float blurDp,
                            int shadowColor) {
        float density = context.getResources().getDisplayMetrics().density;
        this.oval = oval;
        cornerRadius = cornerRadiusDp * density;
        shadowOffsetY = offsetYDp * density;
        // 设计稿模糊值为高斯扩散全宽，BlurMaskFilter 半径约取其一半
        shadowBlurRadius = blurDp * density / 2f;
        this.shadowColor = shadowColor;
        // 位图四周预留空间：3 倍半径覆盖模糊衰减尾部，叠加 Y 偏移
        shadowPad = Math.round(shadowBlurRadius * 3f + shadowOffsetY);
        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 阴影形状在位图中位于 (pad, pad)，绘制时左移 pad、下移 offsetY 即对齐填充形状
        canvas.drawBitmap(shadowBitmap(w, h),
                b.left - shadowPad, b.top - shadowPad + shadowOffsetY, bitmapPaint);
        fillRect.set(b.left, b.top, b.right, b.bottom);
        if (oval) {
            canvas.drawOval(fillRect, fillPaint);
        } else {
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint);
        }
    }

    private Bitmap shadowBitmap(int w, int h) {
        String key = w + "x" + h + (oval ? " oval" : " r" + (int) cornerRadius)
                + " y" + (int) shadowOffsetY + " b" + (int) shadowBlurRadius
                + " c" + Integer.toHexString(shadowColor);
        synchronized (SHADOW_CACHE) {
            Bitmap cached = SHADOW_CACHE.get(key);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(
                w + shadowPad * 2, h + shadowPad * 2, Bitmap.Config.ARGB_8888);
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(shadowColor);
        shadowPaint.setMaskFilter(
                new BlurMaskFilter(shadowBlurRadius, BlurMaskFilter.Blur.NORMAL));
        Canvas bitmapCanvas = new Canvas(bitmap);
        RectF shape = new RectF(shadowPad, shadowPad, shadowPad + w, shadowPad + h);
        if (oval) {
            bitmapCanvas.drawOval(shape, shadowPaint);
        } else {
            bitmapCanvas.drawRoundRect(shape, cornerRadius, cornerRadius, shadowPaint);
        }
        synchronized (SHADOW_CACHE) {
            SHADOW_CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        bitmapPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
