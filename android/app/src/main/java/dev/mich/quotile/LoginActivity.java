package dev.mich.quotile;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
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



/** Explicit, cancellable login only. Neither login method reads quota or schedules work. */
public final class LoginActivity extends Activity {
    public static final String ACTION_USER_LOGIN = "dev.mich.quotile.USER_LOGIN";
    public static final String ACTION_DEVICE_LOGIN = "dev.mich.quotile.DEVICE_LOGIN";
    private final Object sessionLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile AutoCloseable session;
    private volatile int attempt;
    private volatile boolean destroyed;
    private boolean deviceMode, working, visible;
    private TextView title, status, codeView, deviceHelp;
    private ProgressBar progress;
    private Button closeButton, alternateButton, copyButton, browserButton;
    private LinearLayout deviceActions;
    private String currentCode = "";
    private final Runnable stageTick = new Runnable() {
        @Override public void run() {
            if (!visible || !working || destroyed) return;
            updateStage();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        buildScreen();
        String action = getIntent().getAction();
        boolean requested = savedInstanceState == null
                && (ACTION_USER_LOGIN.equals(action) || ACTION_DEVICE_LOGIN.equals(action));
        // Restoration never restarts a login or restores a one-time code.
        setIntent(new Intent(getIntent()).setAction(null));
        if (requested) startLogin(ACTION_DEVICE_LOGIN.equals(action));
        else {
            deviceMode = false;
            alternateButton.setText("使用设备码登录");
            showFailure("上次登录已结束。可使用设备码重新登录。");
        }
    }

    private void startLogin(boolean device) {
        final int id;
        synchronized (sessionLock) {
            id = ++attempt;
            closeSession(session);
            session = null;
        }
        deviceMode = device;
        working = true;
        currentCode = "";
        codeView.setText("");
        codeView.setVisibility(android.view.View.GONE);
        deviceActions.setVisibility(android.view.View.GONE);
        deviceHelp.setVisibility(device ? android.view.View.VISIBLE : android.view.View.GONE);
        title.setText(device ? "设备码登录" : "登录 ChatGPT");
        alternateButton.setText(device ? "改用浏览器登录" : "使用设备码登录");
        closeButton.setText("取消登录");
        progress.setVisibility(android.view.View.VISIBLE);
        status.setText(device ? "正在申请一次性代码…" : "正在准备官方登录页…");
        handler.removeCallbacks(stageTick);
        if (visible) handler.post(stageTick);
        new Thread(() -> login(device, id), "quotile-user-login").start();
    }

    private void login(boolean device, int id) {
        AutoCloseable active = null;
        try {
            synchronized (sessionLock) {
                if (!isCurrent(id)) return;
                active = device ? AccountClient.beginDeviceLogin(getApplicationContext())
                        : AccountClient.beginLogin(getApplicationContext());
                session = active;
            }
            if (device) {
                AccountClient.DeviceLoginSession login = (AccountClient.DeviceLoginSession) active;
                login.requestCode();
                runOnUiThread(() -> {
                    if (!isCurrent(id)) return;
                    currentCode = login.getUserCode();
                    codeView.setText(currentCode);
                    codeView.setVisibility(android.view.View.VISIBLE);
                    deviceActions.setVisibility(android.view.View.VISIBLE);
                    updateStage();
                });
                login.awaitCompletion();
            } else {
                AccountClient.LoginSession login = (AccountClient.LoginSession) active;
                runOnUiThread(() -> {
                    if (!isCurrent(id)) return;
                    if (!openBrowser(login.getAuthorizationUrl())) {
                        synchronized (sessionLock) { ++attempt; closeSession(session); session = null; }
                        showFailure("无法打开浏览器，请安装或启用浏览器后重试。");
                    } else updateStage();
                });
                login.awaitCompletion();
            }
            if (!isCurrent(id)) return;
            QuotaStore store = new QuotaStore(getApplicationContext());
            store.clearSnapshot();
            store.savePreferences(store.theme(), false);
            Schedule.apply(getApplicationContext());
            WidgetUpdate.updateAll(getApplicationContext());
            runOnUiThread(() -> {
                if (!isCurrent(id)) return;
                working = false;
                handler.removeCallbacks(stageTick);
                clearCode();
                Toast.makeText(this, "已登录。点击“刷新额度”后才会读取数据。", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            });
        } catch (AccountClient.AccountException error) {
            String message = loginError(error.getCode(), device, active);
            runOnUiThread(() -> { if (isCurrent(id)) showFailure(message); });
        } catch (Exception error) {
            runOnUiThread(() -> { if (isCurrent(id)) showFailure("登录未完成，请选择另一种登录方式或返回后重试。"); });
        } finally {
            closeSession(active);
            synchronized (sessionLock) { if (session == active) session = null; }
        }
    }

    private boolean isCurrent(int id) {
        return !destroyed && id == attempt;
    }

    private void updateStage() {
        if (!working) return;
        AutoCloseable active = session;
        String stage = active instanceof AccountClient.DeviceLoginSession
                ? ((AccountClient.DeviceLoginSession) active).getStage()
                : active instanceof AccountClient.LoginSession
                ? ((AccountClient.LoginSession) active).getStage() : "preparing";
        String message;
        switch (stage) {
            case "exchanging": message = "已收到授权，正在确认登录…"; break;
            case "saving": message = "正在安全保存登录状态…"; break;
            case "completed": message = "登录完成，正在返回…"; break;
            case "waiting": message = deviceMode
                    ? "复制下方代码，在官方验证网页输入。网页提示成功后返回这里。代码最多有效 15 分钟。"
                    : "等待浏览器授权。请完成网页操作后返回；本次最多等待 3 分钟。若网页一直转圈，可使用下方设备码登录。"; break;
            default: message = deviceMode ? "正在申请一次性代码…" : "正在准备官方登录页…";
        }
        if (!message.contentEquals(status.getText())) status.setText(message);
    }

    private boolean openBrowser(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browser);
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }

    private void copyCode() {
        if (!working || currentCode.isEmpty()) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("余量一次性登录代码", currentCode);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "已复制，请仅在官方验证网页输入", Toast.LENGTH_SHORT).show();
    }

    private void clearCode() {
        currentCode = "";
        codeView.setText("");
        codeView.setVisibility(android.view.View.GONE);
        deviceActions.setVisibility(android.view.View.GONE);
    }

    private static void closeSession(AutoCloseable active) {
        if (active != null) try { active.close(); } catch (Exception ignored) { }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        handler.removeCallbacks(stageTick);
        if (working) handler.post(stageTick);
    }

    @Override protected void onStop() {
        visible = false;
        handler.removeCallbacks(stageTick);
        // The browser may be in front while this explicit login completes.
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(stageTick);
        synchronized (sessionLock) { ++attempt; closeSession(session); session = null; }
        clearCode();
        super.onDestroy();
    }

    @Override public void finish() {
        // A tap on Cancel or Back cancels immediately, before Activity destruction is scheduled.
        destroyed = true;
        handler.removeCallbacks(stageTick);
        synchronized (sessionLock) { ++attempt; closeSession(session); session = null; }
        super.finish();
    }

    private void showFailure(String message) {
        working = false;
        handler.removeCallbacks(stageTick);
        progress.setVisibility(android.view.View.GONE);
        clearCode();
        status.setText(message);
        closeButton.setText("返回");
    }

    private static String loginError(String code, boolean device, AutoCloseable active) {
        if (code == null) return "登录未完成，请返回后重试。";
        switch (code) {
            case "timeout": case "login_timeout": case "device_code_expired":
                if (device) return "本次设备码登录已超时，请返回后重新申请代码。";
                boolean received = active instanceof AccountClient.LoginSession
                        && ((AccountClient.LoginSession) active).hasReceivedCallback();
                return received ? "已收到网页授权，但确认登录超时。可以改用设备码登录。"
                        : "3 分钟内未收到有效的网页授权结果。请使用设备码登录。";
            case "access_denied": case "login_denied": case "login_rejected":
            case "login_cancelled": case "cancelled":
                return "本次登录未完成。需要时可重新登录。";
            case "network": case "network_error": case "network_unavailable": case "network_timeout":
                return "暂时无法连接 OpenAI。请检查网络后重新登录。";
            case "tls_error":
                return "无法建立安全连接，请检查手机时间和当前网络后重试。";
            case "port_in_use": case "callback_unavailable": case "login_listener_unavailable":
                return "本机登录连接无法启动。请使用设备码登录。";
            case "storage_error": case "keystore_error": case "storage_unavailable":
                return "无法安全保存登录状态，请返回后重试。";
            case "device_auth_unavailable": case "device_login_unavailable":
                return "设备码登录暂不可用。请检查 ChatGPT 的安全设置是否允许设备码登录，或改用浏览器登录。";
            case "rate_limited":
                return "登录请求过于频繁，请稍后再试。";
            case "account_not_supported":
                return "此账户暂不支持本机额度接入，请检查登录账号。";
            case "access_unavailable":
                return device ? "OpenAI 暂未允许此次设备码登录。请检查 ChatGPT 的安全设置。"
                        : "OpenAI 暂未允许此次登录，请稍后再试。";
            default:
                return "登录未完成，请选择另一种登录方式或返回后重试。";
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
        title = new TextView(this);
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
        deviceHelp = new TextView(this);
        deviceHelp.setText("首次使用：在 ChatGPT 的设置 → 安全中，允许设备码登录。仅在本次登录期间检查授权结果，取消或结束后停止。");
        deviceHelp.setTextSize(13);
        deviceHelp.setTextColor(muted);
        deviceHelp.setGravity(Gravity.CENTER);
        deviceHelp.setMaxWidth(dp(430));
        deviceHelp.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams help = new LinearLayout.LayoutParams(-1, -2);
        help.topMargin = dp(18);
        content.addView(deviceHelp, help);
        codeView = new TextView(this);
        codeView.setTextSize(30);
        codeView.setTextColor(foreground);
        codeView.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.06f);
        codeView.setPadding(0, dp(20), 0, dp(8));
        content.addView(codeView, new LinearLayout.LayoutParams(-1, -2));
        deviceActions = new LinearLayout(this);
        deviceActions.setOrientation(LinearLayout.VERTICAL);
        deviceActions.setGravity(Gravity.CENTER_HORIZONTAL);
        copyButton = new Button(this);
        copyButton.setText("复制一次性代码");
        copyButton.setAllCaps(false);
        copyButton.setTextColor(foreground);
        copyButton.setOnClickListener(view -> copyCode());
        deviceActions.addView(copyButton, new LinearLayout.LayoutParams(dp(250), dp(50)));
        browserButton = new Button(this);
        browserButton.setText("打开官方验证网页");
        browserButton.setAllCaps(false);
        browserButton.setTextColor(background);
        GradientDrawable primary = new GradientDrawable();
        primary.setColor(foreground);
        primary.setCornerRadius(dp(16));
        browserButton.setBackground(primary);
        browserButton.setOnClickListener(view -> {
            AutoCloseable active = session;
            if (!working || !(active instanceof AccountClient.DeviceLoginSession)) return;
            if (!openBrowser(((AccountClient.DeviceLoginSession) active).getVerificationUrl()))
                Toast.makeText(this, "无法打开浏览器，请安装或启用浏览器", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams open = new LinearLayout.LayoutParams(dp(250), dp(50));
        open.topMargin = dp(8);
        deviceActions.addView(browserButton, open);
        content.addView(deviceActions, new LinearLayout.LayoutParams(-1, -2));
        alternateButton = new Button(this);
        alternateButton.setAllCaps(false);
        alternateButton.setTextSize(14);
        alternateButton.setTextColor(foreground);
        alternateButton.setBackgroundColor(Color.TRANSPARENT);
        alternateButton.setOnClickListener(view -> startLogin(!deviceMode));
        LinearLayout.LayoutParams alternate = new LinearLayout.LayoutParams(dp(250), dp(50));
        alternate.topMargin = dp(18);
        content.addView(alternateButton, alternate);
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
        button.topMargin = dp(8);
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
