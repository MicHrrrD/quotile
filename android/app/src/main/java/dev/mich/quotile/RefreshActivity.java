package dev.mich.quotile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** One quota request, entered only by an intentional tap in the app or home widget. */
public final class RefreshActivity extends Activity {
    public static final String ACTION_USER_REFRESH = "dev.mich.quotile.USER_REFRESH";
    private TextView status;
    private ProgressBar progress;
    private Button closeButton;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        boolean requested = savedInstanceState == null
                && ACTION_USER_REFRESH.equals(getIntent().getAction());
        // Consuming the action prevents rotation, task restoration and resume from repeating it.
        setIntent(new Intent(getIntent()).setAction(null));
        if (!requested) {
            showMessage(QuotaSync.isRunning()
                    ? "刷新请求已发出，完成后小组件会显示结果。"
                    : "本次操作已结束。需要时可返回后再次点击刷新。");
            return;
        }
        QuotaStore store = new QuotaStore(this);
        if (store.demo()) {
            showMessage("当前为演示模式。请在设置中关闭并保存，再刷新真实额度。");
            return;
        }
        if (!AccountClient.isSignedIn(this)) {
            showMessage("请先打开“余量”登录 ChatGPT，再点击刷新额度。");
            return;
        }
        if (QuotaSync.isRunning()) {
            showMessage("本次刷新正在进行，完成后小组件会显示结果。");
            return;
        }
        QuotaSync.refreshAsync(getApplicationContext(), () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            WidgetState state = new QuotaStore(this).state();
            if (state != null && (state.error == null || state.error.isEmpty())
                    && !state.stale && state.updatedAt > 0) {
                finish();
            } else {
                String error = state == null ? "暂未获取到额度，请稍后手动重试。"
                        : MainActivity.errorText(state.error);
                showMessage(error.isEmpty() ? "暂未获取到额度，请稍后手动重试。" : error);
            }
        }));
    }

    // No onResume network work and no automatic retry, even after activity recreation.

    private void showMessage(String value) {
        progress.setVisibility(View.GONE);
        status.setText(value);
        closeButton.setText("关闭");
    }

    private void buildScreen() {
        QuotaStore store = new QuotaStore(this);
        boolean dark = "dark".equals(store.theme()) || ("system".equals(store.theme())
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        int foreground = Color.parseColor(dark ? "#EDF4ED" : "#1D3029");
        int muted = Color.parseColor(dark ? "#ADBEB3" : "#65766D");
        int accent = Color.parseColor(dark ? "#A9D9BD" : "#31694E");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(26), dp(24), dp(22));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor(dark ? "#1A2420" : "#F3F6F3"));
        background.setCornerRadius(dp(25));
        card.setBackground(background);
        TextView title = new TextView(this);
        title.setText("读取额度");
        title.setTextSize(20);
        title.setTextColor(foreground);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(30), dp(30));
        spinner.topMargin = dp(22);
        card.addView(progress, spinner);
        status = new TextView(this);
        status.setText("正在读取这一次的额度…");
        status.setTextColor(muted);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(4), 1f);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams message = new LinearLayout.LayoutParams(-1, -2);
        message.topMargin = dp(18);
        card.addView(status, message);
        closeButton = new Button(this);
        closeButton.setText("关闭窗口");
        closeButton.setAllCaps(false);
        closeButton.setTextSize(14);
        closeButton.setTextColor(accent);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.parseColor(dark ? "#31483A" : "#E1EEE3"));
        buttonBackground.setCornerRadius(dp(14));
        closeButton.setBackground(buttonBackground);
        closeButton.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, dp(48));
        button.topMargin = dp(22);
        card.addView(closeButton, button);
        setContentView(card);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        int width = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(32));
        getWindow().setLayout(Math.max(dp(200), width), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
