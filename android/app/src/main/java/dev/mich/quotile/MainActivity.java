package dev.mich.quotile;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Account, display and opt-in scheduling settings. Opening this screen never reads quota. */
public final class MainActivity extends Activity {
    private QuotaStore store;
    private TextView statusView;
    private TextView accountView;
    private TextView refreshPolicyView;
    private Button loginButton;
    private Button deviceLoginButton;
    private Button disconnectButton;
    private Button refreshButton;
    private Switch demoSwitch;
    private Switch automaticSwitch;
    private LinearLayout intervalGroup;
    private Button[] intervalButtons;
    private LinearLayout page;
    private String chosenTheme;
    private boolean chosenDemo;
    private boolean persistedDemo;
    private boolean chosenAutomatic;
    private int chosenInterval;
    private boolean suppressAutomaticEvents;
    private boolean dark;
    private boolean disconnecting;
    private boolean awaitingLoginReturn;
    private int configWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int background;
    private int surface;
    private int foreground;
    private int muted;
    private int accent;
    private int accentBackground;
    private int inputBackground;
    private final List<ImageView> previews = new ArrayList<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (configWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED,
                    new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configWidgetId));
        }
        store = new QuotaStore(this);
        store.migrateManualMode();
        Schedule.apply(this);
        chosenTheme = savedInstanceState == null ? store.theme()
                : savedInstanceState.getString("chosenTheme", store.theme());
        chosenDemo = savedInstanceState == null ? store.demo()
                : savedInstanceState.getBoolean("chosenDemo", store.demo());
        persistedDemo = savedInstanceState == null ? store.demo()
                : savedInstanceState.getBoolean("persistedDemo", store.demo());
        // Scheduling changes are saved immediately, so restored UI always uses stored choices.
        chosenAutomatic = store.automatic();
        chosenInterval = store.intervalMinutes();
        dark = "dark".equals(chosenTheme) || ("system".equals(chosenTheme) && systemDark());
        setPalette();
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        if (demoSwitch != null && (awaitingLoginReturn || store.demo() != persistedDemo)) {
            awaitingLoginReturn = false;
            chosenDemo = store.demo();
            persistedDemo = chosenDemo;
            demoSwitch.setChecked(chosenDemo);
        }
        if (statusView != null) updateStatus(); // Local state only; no token or quota request.
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("chosenTheme", chosenTheme);
        state.putBoolean("chosenDemo", demoSwitch == null ? chosenDemo : demoSwitch.isChecked());
        state.putBoolean("persistedDemo", persistedDemo);
        super.onSaveInstanceState(state);
    }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setPalette() {
        background = Color.parseColor(dark ? "#181818" : "#F7F7F8");
        surface = Color.parseColor(dark ? "#242424" : "#FFFFFF");
        foreground = Color.parseColor(dark ? "#F3F3F3" : "#181818");
        muted = Color.parseColor(dark ? "#ADADAD" : "#6B6B6B");
        accent = foreground;
        accentBackground = Color.parseColor(dark ? "#333333" : "#EFEFF0");
        inputBackground = Color.parseColor(dark ? "#1E1E1E" : "#F7F7F8");
    }

    private void buildScreen() {
        previews.clear();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(background);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout container = column();
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        page = column();
        page.setPadding(dp(22), dp(22), dp(22), dp(30));
        container.addView(page, new LinearLayout.LayoutParams(-1, -2));
        container.addOnLayoutChangeListener((view, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - container.getPaddingLeft() - container.getPaddingRight();
            if (available < 1) return;
            int width = Math.min(available, dp(640));
            if (page.getLayoutParams().width != width) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) page.getLayoutParams();
                params.width = width;
                page.setLayoutParams(params);
            }
        });
        scroll.addView(container, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }

        TextView eyebrow = text("Quotile", 13, accent, true);
        eyebrow.setLetterSpacing(0.10f);
        page.addView(eyebrow);
        page.addView(text("余量", 34, foreground, true), space(-1, -2, 5));
        page.addView(text("本周还能用多少，抬眼就知道。", 14, muted, false), space(-1, -2, 4));

        LinearLayout account = card();
        page.addView(account, space(-1, -2, 23));
        account.addView(text("Codex 套餐额度", 17, foreground, true));
        account.addView(text("手机本地读取", 12, muted, false), space(-1, -2, 6));
        accountView = text("", 14, foreground, false);
        account.addView(accountView, space(-1, -2, 17));
        account.addView(text("在手机浏览器登录 ChatGPT，登录状态保存在本机。", 13, muted, false), space(-1, -2, 8));
        LinearLayout accountActions = row();
        loginButton = button("登录 ChatGPT", true);
        loginButton.setOnClickListener(view -> startLogin());
        accountActions.addView(loginButton, weighted(1, 48, 0));
        disconnectButton = button("退出登录", false);
        disconnectButton.setOnClickListener(view -> disconnect());
        accountActions.addView(disconnectButton, weighted(1, 48, 10));
        account.addView(accountActions, space(-1, -2, 17));
        deviceLoginButton = button("设备码登录 · 网页卡住时使用", false);
        deviceLoginButton.setOnClickListener(view -> startLogin(true));
        account.addView(deviceLoginButton, space(-1, 48, 10));
        refreshButton = button("刷新额度", false);
        refreshButton.setOnClickListener(view -> refresh());
        account.addView(refreshButton, space(-1, 50, 10));
        refreshPolicyView = text("", 12, muted, false);
        account.addView(refreshPolicyView, space(-1, -2, 11));
        statusView = text("", 13, muted, false);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        account.addView(statusView, space(-1, -2, 15));

        LinearLayout previewCard = card();
        page.addView(previewCard, space(-1, -2, 16));
        previewCard.addView(text("小组件预览", 17, foreground, true));
        previewCard.addView(text("示例数据 · 尺寸按高 × 宽标注", 12, muted, false), space(-1, -2, 5));
        addPreview(previewCard, "1 × 5  ·  一眼看余量", 74);
        addPreview(previewCard, "2 × 5  ·  多一点详情", 156);
        previewCard.addView(text("长按桌面上的小组件，可拖动边缘调整尺寸。", 12, muted, false), space(-1, -2, 14));

        LinearLayout preferences = card();
        page.addView(preferences, space(-1, -2, 16));
        preferences.addView(text("显示与刷新", 17, foreground, true));
        preferences.addView(text("外观", 13, foreground, true), space(-1, -2, 18));
        String[] themeLabels = {"跟随系统", "浅色", "深色"};
        String[] themes = {"system", "light", "dark"};
        addChoices(preferences, themeLabels, indexOf(themes, chosenTheme), index -> {
            chosenTheme = themes[index];
            redrawPreviews();
        });
        automaticSwitch = new Switch(this);
        automaticSwitch.setText("自动刷新（默认关闭）");
        automaticSwitch.setTextSize(14);
        automaticSwitch.setTextColor(foreground);
        automaticSwitch.setMinHeight(dp(48));
        automaticSwitch.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, muted}));
        automaticSwitch.setChecked(chosenAutomatic);
        preferences.addView(automaticSwitch, space(-1, -2, 19));
        preferences.addView(text("切换立即生效。关闭后仅手动读取；开启后由系统安排，省电可能延迟。", 12, muted, false), space(-1, -2, 3));
        intervalGroup = column();
        intervalGroup.addView(text("刷新间隔", 13, foreground, true));
        int[] intervals = {15, 30, 60};
        intervalButtons = addChoices(intervalGroup, new String[]{"15 分钟", "30 分钟", "60 分钟"},
                intervalIndex(chosenInterval), index -> changeInterval(intervals[index]));
        intervalGroup.setVisibility(chosenAutomatic ? View.VISIBLE : View.GONE);
        preferences.addView(intervalGroup, space(-1, -2, 14));
        automaticSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!suppressAutomaticEvents) changeAutomatic(checked);
        });
        demoSwitch = new Switch(this);
        demoSwitch.setText("演示模式");
        demoSwitch.setTextSize(14);
        demoSwitch.setTextColor(foreground);
        demoSwitch.setChecked(chosenDemo);
        demoSwitch.setMinHeight(dp(48));
        demoSwitch.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, muted}));
        preferences.addView(demoSwitch, space(-1, -2, 14));
        preferences.addView(text("桌面明确标注“演示”，示例数字不代表你的账户余量。", 12, muted, false), space(-1, -2, 3));
        Button saveButton = button(configWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                ? "保存设置" : "保存并完成添加", true);
        saveButton.setOnClickListener(view -> savePreferences());
        preferences.addView(saveButton, space(-1, 52, 19));

        if (configWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            LinearLayout addCard = card();
            page.addView(addCard, space(-1, -2, 16));
            addCard.addView(text("放到桌面", 17, foreground, true));
            LinearLayout addRow = row();
            Button slim = button("添加 1 × 5", false);
            slim.setOnClickListener(view -> pin(SlimWidgetProvider.class));
            addRow.addView(slim, weighted(1, 48, 0));
            Button detail = button("添加 2 × 5", false);
            detail.setOnClickListener(view -> pin(DetailWidgetProvider.class));
            addRow.addView(detail, weighted(1, 48, 10));
            addCard.addView(addRow, space(-1, -2, 13));
            addCard.addView(text("内外屏均可使用，实际占格由 One UI 桌面网格决定。", 12, muted, false), space(-1, -2, 11));
        }
        page.addView(text("额度来源：Codex。账户未提供的限额不显示数值。", 12, muted, false), space(-1, -2, 17));
        updateStatus();
    }

    private void addPreview(LinearLayout parent, String title, int heightDp) {
        parent.addView(text(title, 12, muted, true), space(-1, -2, 19));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setTag(heightDp);
        image.setContentDescription(title + "，示例数据预览");
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) renderPreview(image);
        });
        previews.add(image);
        parent.addView(image, space(-1, heightDp, 9));
    }

    private void redrawPreviews() {
        for (ImageView preview : previews) renderPreview(preview);
    }

    private void renderPreview(ImageView preview) {
        if (preview.getWidth() < 1) return;
        boolean previewDark = "dark".equals(chosenTheme) || ("system".equals(chosenTheme) && systemDark());
        int widthDp = Math.max(100, Math.round(preview.getWidth() / getResources().getDisplayMetrics().density));
        int heightDp = (Integer) preview.getTag();
        Bitmap bitmap = WidgetRenderer.render(this, widthDp, heightDp, QuotaStore.demoState(), previewDark);
        preview.setImageBitmap(bitmap);
    }

    private void savePreferences() {
        chosenDemo = demoSwitch.isChecked();
        try {
            store.savePreferences(chosenTheme, chosenDemo, chosenAutomatic, chosenInterval);
            persistedDemo = chosenDemo;
        } catch (Exception error) {
            toast("未能保存设置，请重试");
            return;
        }
        Schedule.apply(this);
        WidgetUpdate.updateAll(this); // Render local cache only, with no network request.
        if (configWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            finishConfiguration();
            return;
        }
        boolean savedDark = "dark".equals(chosenTheme)
                || ("system".equals(chosenTheme) && systemDark());
        if (savedDark != dark) {
            dark = savedDark;
            setPalette();
            buildScreen();
        } else {
            updateStatus();
        }
        toast(chosenDemo ? "已保存，桌面显示演示数据" : "已保存设置");
    }

    private void changeAutomatic(boolean automatic) {
        try {
            store.setAutomatic(automatic, chosenInterval);
            chosenAutomatic = automatic;
            Schedule.apply(this);
        } catch (Exception error) {
            chosenAutomatic = store.automatic();
            suppressAutomaticEvents = true;
            automaticSwitch.setChecked(chosenAutomatic);
            suppressAutomaticEvents = false;
            toast("未能修改自动刷新设置，请重试");
        }
        intervalGroup.setVisibility(chosenAutomatic ? View.VISIBLE : View.GONE);
        updateStatus();
    }

    private void changeInterval(int interval) {
        if (!chosenAutomatic) return;
        try {
            store.setAutomatic(true, interval);
            chosenInterval = interval;
            Schedule.apply(this);
        } catch (Exception error) {
            chosenInterval = store.intervalMinutes();
            paintChoices(intervalButtons, intervalIndex(chosenInterval));
            toast("未能修改刷新间隔，请重试");
        }
        updateStatus();
    }

    private int intervalIndex(int minutes) {
        return minutes == 15 ? 0 : minutes == 60 ? 2 : 1;
    }

    private void startLogin() {
        startLogin(false);
    }

    private void startLogin(boolean deviceCode) {
        if (disconnecting || QuotaSync.isRunning()) {
            toast("请等待当前操作完成");
            return;
        }
        awaitingLoginReturn = true;
        startActivity(new Intent(this, LoginActivity.class).setAction(deviceCode
                ? LoginActivity.ACTION_DEVICE_LOGIN : LoginActivity.ACTION_USER_LOGIN));
    }

    private void refresh() {
        if (disconnecting) return;
        if (store.demo()) {
            toast("请先关闭演示模式并保存，再刷新真实额度");
            return;
        }
        if (!AccountClient.isSignedIn(this)) {
            toast("请先登录 ChatGPT，再点击刷新额度");
            return;
        }
        if (QuotaSync.isRunning()) {
            toast("本次刷新正在进行，请稍候");
            return;
        }
        startActivity(new Intent(this, RefreshActivity.class).setAction(RefreshActivity.ACTION_USER_REFRESH));
    }

    private void disconnect() {
        if (disconnecting) return;
        disconnecting = true;
        updateStatus();
        new Thread(() -> {
            boolean successful = true;
            try {
                AccountClient.logout(getApplicationContext());
                store.clearSnapshot();
                Schedule.apply(getApplicationContext());
            } catch (AccountClient.AccountException error) {
                successful = false;
            }
            WidgetUpdate.updateAll(getApplicationContext());
            boolean result = successful;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                disconnecting = false;
                updateStatus();
                toast(result ? "已退出登录并清除本机额度记录" : "未能退出登录，请重试");
            });
        }, "quotile-signout").start();
    }

    private void updateStatus() {
        boolean signedIn = AccountClient.isSignedIn(this);
        String label = AccountClient.accountLabel(this);
        accountView.setText(signedIn ? (label == null || label.trim().isEmpty()
                ? "已登录 ChatGPT" : label) : "尚未登录");
        loginButton.setText(signedIn ? "重新登录" : "登录 ChatGPT");
        // The click handlers reject overlapping requests. Keep these usable if a refresh
        // window is closed early; there is deliberately no background UI polling timer.
        loginButton.setEnabled(!disconnecting);
        deviceLoginButton.setEnabled(!disconnecting);
        disconnectButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        disconnectButton.setEnabled(!disconnecting);
        refreshButton.setEnabled(!disconnecting);
        refreshPolicyView.setText(store.automatic()
                ? "自动刷新已开启 · 约每 " + store.intervalMinutes() + " 分钟更新，可随时关闭。"
                : "自动刷新已关闭 · 仅在点击“刷新额度”时读取。登录和打开应用不会刷新。");
        statusView.setText(disconnecting ? "正在清除本机登录状态…" : statusText(store.state(), signedIn));
    }

    private String statusText(WidgetState state, boolean signedIn) {
        if (store.demo()) return "演示模式已开启 · 桌面显示示例额度";
        if (!signedIn) return "登录后，点击“刷新额度”读取一次。";
        if (state == null) return "已登录 · 尚未读取额度";
        String error = errorText(state.error);
        String line;
        if (state.weeklyRemaining == null) {
            line = error.isEmpty() ? (state.updatedAt > 0 ? "账户未提供每周额度" : "已登录 · 尚未读取额度")
                    : "暂未获取到额度 · " + error;
        } else {
            line = state.stale || !error.isEmpty() ? "保留上次读取结果" : "显示上次读取的额度";
            if (!error.isEmpty()) line += " · " + error;
        }
        if (state.updatedAt > 0) {
            SimpleDateFormat format = new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            line += "\n读取时间 " + format.format(new Date(state.updatedAt * 1000L)) + " · 北京时间";
        }
        return line;
    }

    static String errorText(String error) {
        if (error == null || error.isEmpty()) return "";
        switch (error.toLowerCase(Locale.ROOT)) {
            case "unauthorized":
            case "auth":
            case "invalid_token":
            case "http_401":
            case "login_required":
            case "not_logged_in":
            case "account_logged_out":
            case "token_expired":
                return "登录已失效，请重新登录";
            case "http_403":
            case "forbidden":
            case "access_denied":
            case "access_unavailable":
                return "账户暂时无法读取此项额度";
            case "account_not_supported":
                return "此账户暂不支持本机额度接入";
            case "network":
            case "network_error":
            case "timeout":
            case "network_timeout":
            case "unreachable":
            case "network_unavailable":
            case "service_unavailable":
            case "upstream_timeout":
            case "upstream_unavailable":
                return "暂时无法连接，请检查网络后手动重试";
            case "tls_error":
            case "unexpected_redirect":
                return "无法建立安全连接，请稍后手动重试";
            case "storage_unavailable":
                return "无法读取本机登录状态，请重新登录";
            case "bucket_unavailable":
                return "所选额度类型暂不可用";
            case "invalid_response":
                return "未收到有效的额度数据";
            case "no_weekly_limit":
            case "no_weekly_window":
            case "unsupported":
            case "quota_window_unavailable":
                return "账户尚未返回所需额度窗口";
            case "stale":
                return "可点击刷新重新读取";
            case "http_429":
            case "rate_limited":
                return "请求过于频繁，请稍后手动重试";
            case "account_changed":
                return "登录状态已变化，请重新点击刷新";
            case "read_cancelled":
                return "本次刷新已取消，可手动重新读取";
            default:
                return "更新未完成，请稍后手动重试";
        }
    }

    private void pin(Class<?> provider) {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager == null || !manager.isRequestPinAppWidgetSupported()) {
            toast("请长按桌面空白处 → 小组件 → 余量，再选择尺寸");
            return;
        }
        try {
            boolean accepted = manager.requestPinAppWidget(new ComponentName(this, provider), null, null);
            if (!accepted) toast("请长按桌面空白处 → 小组件 → 余量，再选择尺寸");
        } catch (RuntimeException error) {
            toast("请长按桌面空白处 → 小组件 → 余量，再选择尺寸");
        }
    }

    private void finishConfiguration() {
        WidgetUpdate.updateAll(this);
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(rounded(surface, 25));
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? (dark ? Color.parseColor("#181818") : Color.WHITE) : accent);
        button.setBackground(rounded(primary ? accent : accentBackground, 16));
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private interface ChoiceListener { void selected(int index); }

    private Button[] addChoices(LinearLayout parent, String[] labels, int initial, ChoiceListener listener) {
        LinearLayout choices = row();
        choices.setPadding(dp(4), dp(4), dp(4), dp(4));
        choices.setBackground(rounded(inputBackground, 15));
        Button[] buttons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button button = button(labels[i], false);
            button.setTextSize(13);
            button.setMinHeight(dp(44));
            buttons[i] = button;
            button.setOnClickListener(view -> {
                paintChoices(buttons, index);
                listener.selected(index);
            });
            choices.addView(button, weighted(1, 44, i == 0 ? 0 : 3));
        }
        paintChoices(buttons, initial);
        parent.addView(choices, space(-1, -2, 9));
        return buttons;
    }

    private void paintChoices(Button[] buttons, int selected) {
        for (int i = 0; i < buttons.length; i++) {
            boolean active = i == selected;
            buttons[i].setBackground(rounded(active ? accentBackground : Color.TRANSPARENT, 11));
            buttons[i].setTextColor(active ? accent : muted);
            buttons[i].setSelected(active);
            buttons[i].setStateDescription(active ? "已选择" : "未选择");
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams space(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams weighted(float weight, int height, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), weight);
        params.leftMargin = dp(left);
        return params;
    }

    private int indexOf(String[] choices, String value) {
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) return i;
        return 0;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
