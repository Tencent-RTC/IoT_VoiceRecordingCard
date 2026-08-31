package com.recorder.client;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 可复用的居中确认/提示浮层内容，仅负责展示文案和派发用户操作。
 *
 * <p>调用方负责将本组件添加到浮层宿主，并处理确认、取消或点击蒙层后的业务逻辑。
 */
public final class ConfirmDialogPanel extends LinearLayout {

    public interface Listener {
        void onConfirmed();

        void onCanceled();
    }

    private TextView title;
    private TextView message;
    private TextView cancel;
    private TextView confirm;
    private Listener listener;
    private boolean decisionMade;

    public ConfirmDialogPanel(Context context) {
        this(context, null);
    }

    public ConfirmDialogPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ConfirmDialogPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_confirm_dialog_panel, this, true);
        title = findViewById(R.id.tvConfirmDialogTitle);
        message = findViewById(R.id.tvConfirmDialogMessage);
        cancel = findViewById(R.id.btnConfirmDialogCancel);
        confirm = findViewById(R.id.btnConfirmDialogAction);
        cancel.setOnClickListener(v -> cancel());
        confirm.setOnClickListener(v -> confirm());
    }

    /**
     * 绑定要展示的内容。{@code cancelText} 为 {@code null} 时展示单操作提示框。
     */
    public void bind(CharSequence titleText, CharSequence messageText, CharSequence cancelText,
                     CharSequence confirmText, Listener listener) {
        this.listener = listener;
        this.decisionMade = false;
        title.setText(titleText);
        title.setVisibility(titleText == null ? View.GONE : View.VISIBLE);
        message.setText(messageText);
        cancel.setText(cancelText);
        boolean showCancel = cancelText != null;
        cancel.setVisibility(showCancel ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams confirmParams = (LinearLayout.LayoutParams)
                confirm.getLayoutParams();
        confirmParams.setMargins(showCancel ? dp(6) : 0, 0, 0, 0);
        confirm.setLayoutParams(confirmParams);
        confirm.setText(confirmText);
    }

    private void confirm() {
        if (decisionMade || listener == null) {
            return;
        }
        decisionMade = true;
        listener.onConfirmed();
    }

    private void cancel() {
        if (decisionMade || listener == null) {
            return;
        }
        decisionMade = true;
        listener.onCanceled();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
