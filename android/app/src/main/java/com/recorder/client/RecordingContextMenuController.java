package com.recorder.client;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.recorder.client.offline.RecordingHistoryStore;

/** 统一管理录音上下文菜单的定位、蒙层和进出场动画。 */
public final class RecordingContextMenuController {

    public interface Listener {
        void onRenameRequested(RecordingHistoryStore.Entry entry);

        void onDeleteRequested(RecordingHistoryStore.Entry entry);
    }

    private static final long ANIMATION_DURATION_MS = 120L;
    private static final float INITIAL_SCALE = 0.92f;

    private final Context context;
    private final FrameLayout overlay;
    private final Listener listener;
    private final Runnable prepareOverlay;

    private View scrim;
    private RecordingContextMenuView menu;
    private boolean dismissAnimating;
    private boolean destroyed;

    public RecordingContextMenuController(Context context, FrameLayout overlay,
                                          Runnable prepareOverlay, Listener listener) {
        this.context = context;
        this.overlay = overlay;
        this.prepareOverlay = prepareOverlay;
        this.listener = listener;
    }

    /** 显示定位到指定列表项旁的菜单，并在展示前准备共享浮层宿主。 */
    public boolean show(RecordingHistoryStore.Entry entry, View anchor) {
        if (destroyed || dismissAnimating || entry == null || anchor == null) {
            return false;
        }
        prepareOverlay.run();
        removeOwnedViews();
        scrim = new View(context);
        scrim.setBackgroundColor(ThemeColorResolver.color(context,
                R.attr.recorderColorOverlayScrim));
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> hide(null));
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        menu = new RecordingContextMenuView(context);
        menu.bind(entry, new RecordingContextMenuView.Listener() {
            @Override
            public void onRenameRequested(RecordingHistoryStore.Entry selected) {
                dismiss(false, () -> listener.onRenameRequested(selected));
            }

            @Override
            public void onDeleteRequested(RecordingHistoryStore.Entry selected) {
                dismiss(false, () -> listener.onDeleteRequested(selected));
            }
        });
        int menuWidth = dp(154);
        menu.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        positionMenu(menuParams, menu.getMeasuredWidth(), menu.getMeasuredHeight(), anchor);
        overlay.addView(menu, menuParams);
        animateEntrance();
        return true;
    }

    /**
     * 关闭菜单；存在菜单时返回 {@code true}，并在退出动画结束后执行回调。
     */
    public boolean hide(Runnable onHidden) {
        if (destroyed || menu == null) {
            return false;
        }
        dismiss(true, onHidden);
        return true;
    }

    /** Activity 销毁时取消动画并移除本控制器持有的 View。 */
    public void destroy() {
        destroyed = true;
        removeOwnedViews();
    }

    private void animateEntrance() {
        float verticalOffset = -dp(4);
        scrim.setAlpha(0f);
        menu.setAlpha(0f);
        menu.setScaleX(INITIAL_SCALE);
        menu.setScaleY(INITIAL_SCALE);
        menu.setTranslationY(verticalOffset);
        overlay.setVisibility(View.VISIBLE);
        scrim.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        menu.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void dismiss(boolean fadeScrim, Runnable onDismissed) {
        if (dismissAnimating || menu == null) {
            return;
        }
        dismissAnimating = true;
        RecordingContextMenuView dismissingMenu = menu;
        View dismissingScrim = scrim;
        if (dismissingScrim != null) {
            dismissingScrim.animate().cancel();
            if (fadeScrim) {
                dismissingScrim.animate()
                        .alpha(0f)
                        .setDuration(ANIMATION_DURATION_MS)
                        .setInterpolator(new AccelerateInterpolator())
                        .start();
            }
        }
        dismissingMenu.animate().cancel();
        dismissingMenu.animate()
                .alpha(0f)
                .scaleX(INITIAL_SCALE)
                .scaleY(INITIAL_SCALE)
                .translationY(-dp(4))
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> finishDismiss(dismissingMenu, dismissingScrim, fadeScrim,
                        onDismissed))
                .start();
    }

    private void finishDismiss(RecordingContextMenuView dismissingMenu, View dismissingScrim,
                               boolean fadeScrim, Runnable onDismissed) {
        if (destroyed || dismissingMenu != menu) {
            return;
        }
        overlay.removeView(dismissingMenu);
        menu = null;
        if (fadeScrim) {
            overlay.removeView(dismissingScrim);
            scrim = null;
            if (overlay.getChildCount() == 0) {
                overlay.setVisibility(View.INVISIBLE);
            }
        }
        dismissAnimating = false;
        if (onDismissed != null) {
            onDismissed.run();
        }
    }

    private void removeOwnedViews() {
        dismissAnimating = false;
        if (menu != null) {
            menu.animate().cancel();
            overlay.removeView(menu);
            menu = null;
        }
        if (scrim != null) {
            scrim.animate().cancel();
            overlay.removeView(scrim);
            scrim = null;
        }
    }

    private void positionMenu(FrameLayout.LayoutParams params, int menuWidth, int menuHeight,
                              View anchor) {
        int[] overlayLocation = new int[2];
        int[] anchorLocation = new int[2];
        overlay.getLocationOnScreen(overlayLocation);
        anchor.getLocationOnScreen(anchorLocation);
        Rect visibleWindow = new Rect();
        overlay.getWindowVisibleDisplayFrame(visibleWindow);
        int horizontalMargin = dp(12);
        int verticalMargin = dp(12);
        int safeLeft = Math.max(horizontalMargin,
                visibleWindow.left - overlayLocation[0] + horizontalMargin);
        int safeRight = Math.min(overlay.getWidth() - horizontalMargin,
                visibleWindow.right - overlayLocation[0] - horizontalMargin);
        int safeTop = Math.max(verticalMargin,
                visibleWindow.top - overlayLocation[1] + verticalMargin);
        int safeBottom = Math.min(overlay.getHeight() - verticalMargin,
                visibleWindow.bottom - overlayLocation[1] - verticalMargin);
        int desiredLeft = anchorLocation[0] - overlayLocation[0] + anchor.getWidth()
                - menuWidth - dp(12);
        int desiredTop = anchorLocation[1] - overlayLocation[1] + anchor.getHeight() / 2;
        int maxLeft = Math.max(safeLeft, safeRight - menuWidth);
        int maxTop = Math.max(safeTop, safeBottom - menuHeight);
        params.leftMargin = Math.max(safeLeft, Math.min(desiredLeft, maxLeft));
        params.topMargin = Math.max(safeTop, Math.min(desiredTop, maxTop));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
