package com.recorder.client;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * 内容刷新后保持底部、同时屏蔽子 View 焦点自动滚动的 ScrollView。
 *
 * <p>默认跟随内容底部。用户手势上翻时暂停跟随；用户滚回底部后自动恢复。
 * 钉底在 {@link #onLayout(boolean, int, int, int, int)} 完成，确保使用的是
 * setText 后的新内容高度，而不是下一次布局前的旧高度。
 */
public final class NoChildAutoScrollView extends ScrollView {

    private boolean pinnedToBottom = true;
    private boolean userTouching;
    private boolean restoringBottom;
    private int bottomSlopPx;

    public NoChildAutoScrollView(Context context) {
        super(context);
        init(context);
    }

    public NoChildAutoScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NoChildAutoScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        bottomSlopPx = (int) (24 * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * TextView.setText 会先 requestLayout；只有 onLayout 结束后子 View 才具有新高度。
     * 在这里同步钉底，避免使用旧高度滚动后又被下一帧的新布局甩离底部。
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        restoreBottomIfNeeded();
    }

    /** dispatchTouchEvent 能看到被可选 TextView 消费的 DOWN，可靠识别用户主动操作。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            userTouching = true;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            userTouching = false;
            if (isAtBottom()) {
                pinnedToBottom = true;
            }
        }
        return handled;
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (restoringBottom) {
            return;
        }
        if (userTouching) {
            pinnedToBottom = isAtBottom();
        } else if (pinnedToBottom) {
            // 防御框架在 layout 之后发起的焦点、选区和可见区域滚动。
            restoreBottomIfNeeded();
        } else if (isAtBottom()) {
            // 用户向下惯性滑动到底时恢复自动跟随。
            pinnedToBottom = true;
        }
    }

    /** 非用户操作期间，子 View 的焦点/光标不得改变容器滚动位置。 */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return userTouching ? super.computeScrollDeltaToGetChildRectOnScreen(rect) : 0;
    }

    /** 部分 Android 版本会绕过 computeScrollDelta，直接走该入口，因此一并保护。 */
    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return userTouching
                && super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    private boolean isAtBottom() {
        return getScrollY() >= getScrollRange() - bottomSlopPx;
    }

    private int getScrollRange() {
        View child = getChildAt(0);
        if (child == null) {
            return 0;
        }
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        int contentHeight = child.getHeight() + params.topMargin + params.bottomMargin;
        int viewportHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(0, contentHeight - viewportHeight);
    }

    private void restoreBottomIfNeeded() {
        if (!pinnedToBottom || userTouching || restoringBottom) {
            return;
        }
        int bottom = getScrollRange();
        if (getScrollY() == bottom) {
            return;
        }
        restoringBottom = true;
        scrollTo(getScrollX(), bottom);
        restoringBottom = false;
    }
}
