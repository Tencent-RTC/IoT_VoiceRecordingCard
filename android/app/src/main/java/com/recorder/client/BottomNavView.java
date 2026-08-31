package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 底部固定导航栏：录音 / 录音文件 / 我的。
 *
 * <p>「我的」当前仅为占位入口，点击不触发任何功能；后续接入"我的"
 * 页面 Destination 时，按 {@link #TAB_RECORD} / {@link #TAB_FILES} 同样的模式扩展。
 */
public final class BottomNavView extends LinearLayout {

    public static final int TAB_RECORD = 0;
    public static final int TAB_FILES = 1;
    public static final int TAB_USER = 2;

    public interface OnTabSelectedListener {
        void onTabSelected(int tab);
    }

    private static final int[] ICON_ON = {
            R.drawable.ic_nav_record_on, R.drawable.ic_nav_file_on, R.drawable.ic_nav_user_on};
    private static final int[] ICON_OFF = {
            R.drawable.ic_nav_record_off, R.drawable.ic_nav_file_off, R.drawable.ic_nav_user_off};
    private static final String[] TITLES = {"录音", "录音文件", "我的"};

    private final ImageView[] icons = new ImageView[3];
    private final TextView[] labels = new TextView[3];
    private int selectedTab = TAB_RECORD;
    private OnTabSelectedListener listener;

    public BottomNavView(Context context) {
        this(context, null);
    }

    public BottomNavView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(ThemeColorResolver.color(context, R.attr.recorderColorSurface));
        for (int tab = 0; tab < 3; tab++) {
            addView(buildItem(context, tab));
        }
        applySelection();
    }

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    public int getSelectedTab() {
        return selectedTab;
    }

    public void setSelectedTab(int tab) {
        if (tab == TAB_USER || tab == selectedTab) {
            return;
        }
        selectedTab = tab;
        applySelection();
    }

    private LinearLayout buildItem(Context context, int tab) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        int padV = dp(10);
        item.setPadding(0, padV, 0, dp(12));

        ImageView icon = new ImageView(context);
        int iconSize = dp(24);
        LayoutParams iconLp = new LayoutParams(iconSize, iconSize);
        icon.setLayoutParams(iconLp);
        icons[tab] = icon;

        TextView label = new TextView(context);
        LayoutParams labelLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(3);
        label.setLayoutParams(labelLp);
        label.setText(TITLES[tab]);
        label.setTextSize(12f);
        labels[tab] = label;

        item.addView(icon);
        item.addView(label);
        LayoutParams itemLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        item.setLayoutParams(itemLp);
        item.setOnClickListener(v -> onItemClicked(tab));
        return item;
    }

    private void onItemClicked(int tab) {
        if (tab == TAB_USER) {
            // 「我的」暂为占位，不实现任何功能。
            return;
        }
        if (tab == selectedTab) {
            return;
        }
        selectedTab = tab;
        applySelection();
        if (listener != null) {
            listener.onTabSelected(tab);
        }
    }

    private void applySelection() {
        for (int tab = 0; tab < 3; tab++) {
            boolean selected = tab == selectedTab;
            icons[tab].setImageResource(selected ? ICON_ON[tab] : ICON_OFF[tab]);
            labels[tab].setTextColor(ThemeColorResolver.color(getContext(), selected
                    ? R.attr.recorderColorPrimary : R.attr.recorderColorTextPending));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
