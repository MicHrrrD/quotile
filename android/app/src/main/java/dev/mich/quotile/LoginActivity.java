package dev.mich.quotile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/** An explicitly requested, bounded browser login. It never requests account quota. */
public final class LoginActivity extends Activity {
    public static final String ACTION_USER_LOGIN = "dev.mich.quotile.USER_LOGIN";
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile AccountClient.LoginSession session;
    private TextView status;
    private ProgressBar progress;
    private Button closeButton;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        buildScreen();
        boolean requested = savedInstanceState == null
                && ACTION_USER_LOGIN.equals(getIntent().getAction());
        // An activity restoration or return from the browser cannot initiate another login.
        setIntent(new Intent(getIntent()).setAction(null));
        if (!requested) {
            showFailure("登录未继续，请返回后重新点击“登录 ChatGPT”。");
            return;
        }
        new Thread(this::login, "quotile-browser-login").start();
    }

    private void login() {
        AccountClient.LoginSession active = null;
        try {
            active = AccountClient.beginLogin(getApplicationContext());
            session = active;
            if (closed.get()) {
                active.close();
                return;
            }
            final AccountClient.LoginSession ready = active;
            runOnUiThread(() -> {
                if (closed.get() || isFinishing() || isDestroyed()) return;
                try {
                    Intent browser = new Intent(Intent.ACTION_VIEW,
                            Uri.parse(ready.getAuthorizationUrl()));
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(browser);
                    status.setText("请在浏览器完成 OpenAI 登录，然后返回这里。登录过程最多等待 3 分钟。");
                } catch (RuntimeException error) {
                    closed.set(true);
                    ready.close();
                    showFailure("无法打开浏览器，请安装或启用浏览器后重试。");
                }
            });
            active.awaitCompletion();
            if (closed.get()) return;
            QuotaStore store = new QuotaStore(getApplicationContext());
            store.clearSnapshot();
            store.savePreferences(store.theme(), false);
            Schedule.apply(getApplicationContext());
            WidgetUpdate.updateAll(getApplicationContext());
            runOnUiThread(() -> {
                if (closed.get() || isFinishing() || isDestroyed()) return;
                Toast.makeText(this, "已登录。点击“刷新额度”后才会读取数据。", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            });
        } catch (AccountClient.AccountException error) {
            String message = loginError(error.getCode());
            runOnUiThread(() -> {
                if (!closed.get() && !isFinishing() && !isDestroyed()) showFailure(message);
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                if (!closed.get() && !isFinishing() && !isDestroyed()) {
                    showFailure("登录未完成，请返回后重新尝试。");
                }
            });
        } finally {
            if (active != null) active.close();
            session = null;
        }
    }

    @Override protected void onDestroy() {
        closed.set(true);
        AccountClient.LoginSession active = session;
        if (active != null) active.close();
        super.onDestroy();
    }

    // onStop intentionally does nothing: the external browser must remain usable.

    private void showFailure(String message) {
        progress.setVisibility(android.view.View.GONE);
        status.setText(message);
        closeButton.setText("返回");
    }

    private static String loginError(String code) {
        if (code == null) return "登录未完成，请返回后重新尝试。";
        switch (code) {
            case "timeout":
            case "login_timeout":
                return "本次登录已超时，请返回后重新点击“登录 ChatGPT”。";
            case "access_denied":
            case "login_denied":
            case "login_rejected":
            case "login_cancelled":
            case "cancelled":
                return "本次登录未完成。需要时可返回后重新登录。";
            case "network":
            case "network_error":
            case "network_unavailable":
            case "network_timeout":
                return "暂时无法连接 OpenAI，请检查网络后重新登录。";
            case "port_in_use":
            case "callback_unavailable":
            case "login_listener_unavailable":
                return "本机登录连接暂时无法启动，请关闭其他登录页面后重试。";
            case "storage_error":
            case "keystore_error":
            case "storage_unavailable":
                return "无法安全保存登录状态，请返回后重试。";
            case "account_not_supported":
                return "此账户暂不支持本机额度接入，请返回后检查登录账号。";
            case "access_unavailable":
                return "OpenAI 暂未允许此次登录，请返回后重试。";
            default:
                return "登录未完成，请返回后重新尝试。";
        }
    }

    private void buildScreen() {
        QuotaStore store = new QuotaStore(this);
        boolean dark = "dark".equals(store.theme()) || ("system".equals(store.theme())
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        int background = Color.parseColor(dark ? "#242424" : "#F7F7F8");
        int foreground = Color.parseColor(dark ? "#F3F3F3" : "#181818");
        int muted = Color.parseColor(dark ? "#ADADAD" : "#6B6B6B");
        int accent = Color.parseColor(dark ? "#F3F3F3" : "#181818");
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER);
        shell.setPadding(dp(28), dp(32), dp(28), dp(32));
        shell.setBackgroundColor(background);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        shell.addView(content, new LinearLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText("登录 ChatGPT");
        title.setTextSize(24);
        title.setTextColor(foreground);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title);
        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(34), dp(34));
        spinner.topMargin = dp(28);
        content.addView(progress, spinner);
        status = new TextView(this);
        status.setText("正在准备打开官方登录页…");
        status.setTextSize(15);
        status.setTextColor(muted);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(5), 1f);
        status.setMaxWidth(dp(430));
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams message = new LinearLayout.LayoutParams(-1, -2);
        message.topMargin = dp(24);
        content.addView(status, message);
        closeButton = new Button(this);
        closeButton.setText("取消登录");
        closeButton.setTextSize(14);
        closeButton.setAllCaps(false);
        closeButton.setTextColor(accent);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(dark ? "#333333" : "#EFEFF0"));
        shape.setCornerRadius(dp(16));
        closeButton.setBackground(shape);
        closeButton.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(dp(220), dp(50));
        button.topMargin = dp(28);
        content.addView(closeButton, button);
        scroll.addView(shell, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
