package com.recorder.client;

import android.content.Context;

/**
 * 白色圆角卡片 + 设计稿外阴影，是 {@link ShadowedDrawable} 的语义封装，
 * 供各页面卡片统一使用。覆盖设计稿卡片全部三种外阴影规格（均为 X=0、扩散=0）：
 * <ul>
 *   <li>{@link #standard}：Y6 / 模糊18 / 对应 Theme Token 6%（录音列表项、实时录音转写卡）
 *   <li>{@link #prominent}：Y8 / 模糊24 / 对应 Theme Token 8%（首页设备卡、实时录音计时卡、设备信息卡）
 *   <li>{@link 对应 Theme Tokennt}：Y6 / 模糊18 / 对应 Theme Token 6%（绑定页设备行）
 * </ul>
 *
 * <p>阴影会向视图边界外溢出（Y 偏移 + 模糊扩散），依赖父布局
 * clipChildren=false 配合。
 */
public final class CardShadowDrawable extends ShadowedDrawable {

    /** 标准阴影。 */
    public static CardShadowDrawable standard(Context context, float cornerRadiusDp) {
        return new CardShadowDrawable(context, cornerRadiusDp, 6f, 18f,
                ThemeColorResolver.color(context, R.attr.recorderColorShadowNeutralSmall));
    }

    /** 强调阴影。 */
    public static CardShadowDrawable prominent(Context context, float cornerRadiusDp) {
        return new CardShadowDrawable(context, cornerRadiusDp, 8f, 24f,
                ThemeColorResolver.color(context, R.attr.recorderColorShadowNeutralMedium));
    }

    /** 品牌色阴影。 */
    public static CardShadowDrawable accent(Context context, float cornerRadiusDp) {
        return new CardShadowDrawable(context, cornerRadiusDp, 6f, 18f,
                ThemeColorResolver.color(context, R.attr.recorderColorShadowAccent));
    }

    private CardShadowDrawable(Context context, float cornerRadiusDp,
                               float offsetYDp, float blurDp, int shadowColor) {
        super(context, ThemeColorResolver.color(context, R.attr.recorderColorSurface),
                false, cornerRadiusDp, offsetYDp, blurDp, shadowColor);
    }
}
