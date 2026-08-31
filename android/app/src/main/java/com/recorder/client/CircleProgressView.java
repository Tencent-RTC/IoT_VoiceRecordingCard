package com.recorder.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 录音导入进度环：细圆环 + 中心百分比文本，对应设计稿"68%"样式。
 *
 * <p>注意：不要改用带 {@code Paint.Cap.ROUND} 的 stroke 弧——部分机型的硬件
 * 加速会忽略弧的笔帽设置，把起点笔帽渲染成方形块（环左上角出现方形阴影）。
 * 这里轨道用闭合 {@code drawOval}（无笔帽），进度用填充式环形扇区 Path
 * （无 stroke/笔帽），两端圆角由端点实心圆模拟，渲染行为在所有机型上确定。
 */
public final class CircleProgressView extends View {

    private static final float START_ANGLE = -90f;

    private final Paint trackPaint;
    private final Paint fillPaint;
    private final Paint textPaint;
    private final RectF midOval = new RectF();
    private final RectF outerOval = new RectF();
    private final RectF innerOval = new RectF();
    private final Path sector = new Path();
    private final Rect textBounds = new Rect();
    private final float strokeWidth;
    /** 0..100 */
    private float percent;

    public CircleProgressView(Context context) {
        this(context, null);
    }

    public CircleProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        strokeWidth = 3.5f * density;

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setColor(ThemeColorResolver.color(context,
                R.attr.recorderColorStatusSuccessContainer));

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ThemeColorResolver.color(context, R.attr.recorderColorPrimary));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(ThemeColorResolver.color(context, R.attr.recorderColorPrimary));
        textPaint.setTextSize(11f * density);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setProgress(float percent) {
        this.percent = Math.max(0f, Math.min(100f, percent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float strokeHalf = strokeWidth / 2f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float rMid = Math.min(getWidth(), getHeight()) / 2f - strokeHalf;

        // 满环轨道（闭合椭圆，不涉及笔帽）。
        midOval.set(cx - rMid, cy - rMid, cx + rMid, cy + rMid);
        canvas.drawOval(midOval, trackPaint);

        float sweep = 3.6f * percent;
        if (sweep > 0f && rMid > strokeHalf) {
            // 填充式环形扇区：外弧前进、内弧返回，两端为径向平边，无笔帽参与。
            outerOval.set(cx - rMid - strokeHalf, cy - rMid - strokeHalf,
                    cx + rMid + strokeHalf, cy + rMid + strokeHalf);
            innerOval.set(cx - rMid + strokeHalf, cy - rMid + strokeHalf,
                    cx + rMid - strokeHalf, cy + rMid - strokeHalf);
            sector.reset();
            sector.addArc(outerOval, START_ANGLE, sweep);
            sector.arcTo(innerOval, START_ANGLE + sweep, -sweep);
            sector.close();
            canvas.drawPath(sector, fillPaint);

            // 端点圆角：中线上两端各叠一个半径为半线宽的实心圆。
            drawCapCircle(canvas, cx, cy, rMid, START_ANGLE, strokeHalf);
            if (sweep < 360f) {
                drawCapCircle(canvas, cx, cy, rMid, START_ANGLE + sweep, strokeHalf);
            }
        }

        String text = Math.round(percent) + "%";
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        canvas.drawText(text, cx, cy + textBounds.height() / 2f, textPaint);
    }

    private void drawCapCircle(Canvas canvas, float cx, float cy, float rMid,
                               float angleDeg, float radius) {
        double rad = Math.toRadians(angleDeg);
        canvas.drawCircle((float) (cx + rMid * Math.cos(rad)),
                (float) (cy + rMid * Math.sin(rad)), radius, fillPaint);
    }
}
