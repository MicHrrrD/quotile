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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Native, accessible setup screen. OpenAI credentials never belong on this screen. */
public final class MainActivity extends Activity {
    private QuotaStore store;
    private EditText endpointInput;
    private EditText tokenInput;
    private TextView statusView;
    private Button saveButton;
    private Button refreshButton;
    private Switch demoSwitch;
    private LinearLayout page;
    private String chosenTheme;
    private int chosenInterval;
    private boolean dark;
    private boolean refreshing;
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
            Intent cancelled = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configWidgetId);
            setResult(RESULT_CANCELED, cancelled);
        }
        store = new QuotaStore(this);
        chosenTheme = store.theme();
        chosenInterval = store.intervalMinutes();
        dark = "dark".equals(chosenTheme) || ("system".equals(chosenTheme) && systemDark());
        setPalette();
        buildScreen();
        Schedule.ensure(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setPalette() {
        background = Color.parseColor(dark ? "#111816" : "#F3F6F3");
        surface = Color.parseColor(dark ? "#1A2420" : "#FFFFFF");
        foreground = Color.parseColor(dark ? "#EDF4ED" : "#1D3029");
        muted = Color.parseColor(dark ? "#ADBEB3" : "#65766D");
        accent = Color.parseColor(dark ? "#A9D9BD" : "#31694E");
        accentBackground = Color.parseColor(dark ? "#31483A" : "#E1EEE3");
        inputBackground = Color.parseColor(dark ? "#111B16" : "#F5F8F5");
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
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        page = column();
        page.setPadding(dp(22), dp(22), dp(22), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        TextView eyebrow = text("Quotile", 13, accent, true);
        eyebrow.setLetterSpacing(0.10f);
        page.addView(eyebrow);
        page.addView(text("余量", 34, foreground, true), space(-1, -2, 5));
        page.addView(text("本周还能用多少，抬眼就知道。", 14, muted, false), space(-1, -2, 4));

        LinearLayout previewCard = card();
        page.addView(previewCard, space(-1, -2, 23));
        previewCard.addView(text("小组件预览", 17, foreground, true));
        previewCard.addView(text("示例数据 · 尺寸按高 × 宽标注", 12, muted, false), space(-1, -2, 5));
        addPreview(previewCard, "1 × 5  ·  一眼看余量", 74);
        addPreview(previewCard, "2 × 5  ·  多一点详情", 156);
        previewCard.addView(text("长按桌面上的小组件，可拖动边缘调整尺寸。", 12, muted, false), space(-1, -2, 14));

        LinearLayout connect = card();
        page.addView(connect, space(-1, -2, 16));
        connect.addView(text("连接你的额度", 17, foreground, true));
        connect.addView(text("在自己的桥接主机登录账户，再填写服务地址和配对码。获取方式见附带说明。", 13, muted, false), space(-1, -2, 8));
        connect.addView(text("服务地址", 13, foreground, true), space(-1, -2, 19));
        endpointInput = input("https://你的服务地址", false);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setText(store.endpoint());
        connect.addView(endpointInput, space(-1, 52, 8));
        connect.addView(text("配对码", 13, foreground, true), space(-1, -2, 15));
        tokenInput = input("输入桥接服务生成的配对码", true);
        tokenInput.setText(store.token());
        connect.addView(tokenInput, space(-1, 52, 8));
        connect.addView(text("这里不需要填写 OpenAI 密码或 API Key。", 12, muted, false), space(-1, -2, 9));

        LinearLayout preferences = card();
        page.addView(preferences, space(-1, -2, 16));
        preferences.addView(text("显示与更新", 17, foreground, true));
        preferences.addView(text("外观", 13, foreground, true), space(-1, -2, 18));
        String[] themeLabels = {"跟随系统", "浅色", "深色"};
        String[] themes = {"system", "light", "dark"};
        addChoices(preferences, themeLabels, indexOf(themes, chosenTheme), index -> {
            chosenTheme = themes[index];
            redrawPreviews();
        });
        preferences.addView(text("自动更新间隔", 13, foreground, true), space(-1, -2, 18));
        int[] intervals = {15, 30, 60};
        int selected = chosenInterval == 15 ? 0 : chosenInterval == 60 ? 2 : 1;
        addChoices(preferences, new String[]{"15 分钟", "30 分钟", "60 分钟"}, selected,
                index -> chosenInterval = intervals[index]);
        preferences.addView(text("省电模式可能延迟更新；点击小组件的刷新按钮可手动更新。", 12, muted, false), space(-1, -2, 10));
        demoSwitch = new Switch(this);
        demoSwitch.setText("演示模式");
        demoSwitch.setTextSize(14);
        demoSwitch.setTextColor(foreground);
        demoSwitch.setChecked(store.demo());
        demoSwitch.setMinHeight(dp(48));
        demoSwitch.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, muted}));
        preferences.addView(demoSwitch, space(-1, -2, 14));
        preferences.addView(text("开启后，桌面会明确标注“演示”，并显示示例额度。", 12, muted, false), space(-1, -2, 3));

        statusView = text("", 13, muted, false);
        page.addView(statusView, space(-1, -2, 20));
        saveButton = button(configWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID ? "保存并连接" : "保存并完成添加", true);
        saveButton.setOnClickListener(view -> saveAndConnect());
        page.addView(saveButton, space(-1, 52, 13));
        LinearLayout actionRow = row();
        refreshButton = button("立即刷新", false);
        refreshButton.setOnClickListener(view -> refresh());
        actionRow.addView(refreshButton, weighted(1, 48, 0));
        Button disconnect = button("断开连接", false);
        disconnect.setOnClickListener(view -> disconnect());
        actionRow.addView(disconnect, weighted(1, 48, 10));
        page.addView(actionRow, space(-1, -2, 10));

        if (configWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            LinearLayout addCard = card();
            page.addView(addCard, space(-1, -2, 20));
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
        } else {
            Button later = button("稍后连接，先添加小组件", false);
            later.setOnClickListener(view -> finishConfiguration());
            page.addView(later, space(-1, 48, 10));
        }
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

    private void saveAndConnect() {
        String endpoint = endpointInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        boolean demo = demoSwitch.isChecked();
        endpointInput.setError(null);
        tokenInput.setError(null);
        if (!demo && endpoint.isEmpty()) {
            endpointInput.setError("请填写服务地址，或开启演示模式");
            endpointInput.requestFocus();
            return;
        }
        if (!demo && token.isEmpty()) {
            tokenInput.setError("请填写配对码");
            tokenInput.requestFocus();
            return;
        }
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_-]{32,256}")) {
            tokenInput.setError("请粘贴桥接服务生成的完整配对码");
            tokenInput.requestFocus();
            return;
        }
        if (!endpoint.isEmpty()) {
            try {
                endpoint = QuotaStore.normalizeEndpoint(endpoint);
            } catch (IllegalArgumentException error) {
                endpointInput.setError("请填写有效的 HTTPS 服务地址");
                endpointInput.requestFocus();
                return;
            }
        }
        try {
            store.configure(endpoint, token, chosenTheme, chosenInterval, demo);
        } catch (Exception error) {
            toast("未能保存连接设置，请重试");
            return;
        }
        boolean savedDark = "dark".equals(chosenTheme)
                || ("system".equals(chosenTheme) && systemDark());
        if (savedDark != dark) {
            dark = savedDark;
            setPalette();
            buildScreen();
        }
        tokenInput.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(tokenInput.getWindowToken(), 0);
        Schedule.ensure(this);
        WidgetUpdate.updateAll(this);
        if (configWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (!demo) QuotaSync.refreshAsync(getApplicationContext(), () -> { });
            finishConfiguration();
        } else if (demo) {
            updateStatus();
            toast("已保存，桌面显示演示数据");
        } else {
            refresh();
        }
    }

    private void refresh() {
        if (refreshing) return;
        if (store.demo()) {
            WidgetUpdate.updateAll(this);
            updateStatus();
            toast("当前为演示模式，关闭后可同步真实额度");
            return;
        }
        if (store.endpoint().isEmpty() || store.token().isEmpty()) {
            toast("请先填写连接信息并保存");
            return;
        }
        refreshing = true;
        saveButton.setEnabled(false);
        refreshButton.setEnabled(false);
        refreshButton.setText("正在更新…");
        statusView.setText("正在连接你的额度服务…");
        QuotaSync.refreshAsync(getApplicationContext(), () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshing = false;
            saveButton.setEnabled(true);
            refreshButton.setEnabled(true);
            refreshButton.setText("立即刷新");
            updateStatus();
        }));
    }

    private void disconnect() {
        store.clear();
        endpointInput.setText("");
        tokenInput.setText("");
        demoSwitch.setChecked(false);
        Schedule.ensure(this);
        WidgetUpdate.updateAll(this);
        updateStatus();
        toast("已断开连接并清除配对码");
    }

    private void updateStatus() {
        // The status model is supplied by the same source as the home-screen widget.
        WidgetState state = store.state();
        statusView.setText(statusText(state));
    }

    private String statusText(WidgetState state) {
        if (store.demo()) return "演示模式已开启 · 桌面显示示例额度";
        if (store.endpoint().isEmpty() || store.token().isEmpty()) return "尚未连接 · 保存后开始自动更新";
        if (state == null) return "已保存连接 · 等待首次更新";
        String error = errorText(state.error);
        String line;
        if (state.weeklyRemaining == null) {
            line = error.isEmpty() ? (state.updatedAt > 0 ? "已同步 · 账户未提供每周额度" : "已保存连接 · 等待首次更新") : "暂未获取到额度 · " + error;
        } else {
            line = state.stale || !error.isEmpty() ? "显示上次数据" : "已连接 · 额度已同步";
            if (!error.isEmpty()) line += " · " + error;
        }
        if (state.updatedAt > 0) {
            long milliseconds = state.updatedAt * 1000L;
            SimpleDateFormat format = new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            line += "\n最近更新 " + format.format(new Date(milliseconds)) + " · 北京时间";
        }
        return line;
    }

    private String errorText(String error) {
        if (error == null || error.isEmpty()) return "";
        switch (error.toLowerCase(Locale.ROOT)) {
            case "unauthorized":
            case "auth":
            case "invalid_token":
            case "http_401":
            case "http_403":
            case "pairing_rejected":
                return "请检查配对码";
            case "login_required":
            case "not_logged_in":
            case "account_logged_out":
                return "请在桥接主机重新登录账户";
            case "network":
            case "network_error":
            case "timeout":
            case "unreachable":
            case "network_unavailable":
            case "service_unavailable":
                return "服务暂时无法连接";
            case "tls_error":
                return "服务的安全连接无法建立";
            case "upstream_timeout":
            case "upstream_unavailable":
                return "额度来源暂时无法连接";
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
                return "等待下一次更新";
            default:
                return "更新未完成，请稍后重试";
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

    private EditText input(String hint, boolean secret) {
        EditText view = new EditText(this);
        view.setTextSize(14);
        view.setTextColor(foreground);
        view.setHintTextColor(muted);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(rounded(inputBackground, 14));
        view.setSelectAllOnFocus(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        view.setSaveEnabled(false);
        if (secret) {
            view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            view.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        } else {
            view.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        }
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? (dark ? Color.parseColor("#152C20") : Color.WHITE) : accent);
        button.setBackground(rounded(primary ? accent : accentBackground, 16));
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private interface ChoiceListener { void selected(int index); }

    private void addChoices(LinearLayout parent, String[] labels, int initial, ChoiceListener listener) {
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
