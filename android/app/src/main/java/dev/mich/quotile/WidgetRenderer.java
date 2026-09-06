package dev.mich.quotile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Real launcher views: text and capsule bars stay sharp at the host's native density. */
public final class WidgetRenderer {
    private final RemoteViews views;
    private final float width, height;
    private final WidgetState state;
    private final boolean refreshing;
    private final boolean reveal;
    private final boolean dark;
    private final float density;
    private float contentOffsetY;
    private final long now = System.currentTimeMillis() / 1000L;
    private final int ink, secondary, muted, track, accent;

    private WidgetRenderer(Context context, int widthDp, int heightDp, WidgetState snapshot,
                           boolean dark, boolean busy, boolean animateRefresh, boolean reveal) {
        width = Math.max(110, Math.min(700, widthDp));
        height = Math.max(40, Math.min(300, heightDp));
        state = snapshot == null ? new WidgetState() : snapshot;
        refreshing = busy;
        this.dark = dark;
        this.reveal = reveal && animateRefresh && !busy;
        density = context.getResources().getDisplayMetrics().density;
        ink = Color.parseColor(dark ? "#F5F5F5" : "#181818");
        secondary = Color.parseColor(dark ? "#B5B5B5" : "#5E5E5E");
        muted = Color.parseColor(dark ? "#A3A3A3" : "#707070");
        track = Color.parseColor(dark ? "#414141" : "#E2E2E5");
        accent = Color.parseColor(dark ? "#ECECEC" : "#282828");
        views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setInt(R.id.widget_root, "setBackgroundResource",
                dark ? R.drawable.widget_background_dark : R.drawable.widget_background);
        views.setImageViewResource(R.id.widget_source_icon,
                dark ? R.drawable.ic_openai_source_dark : R.drawable.ic_openai_source);
        // Launchers can reapply onto the existing hierarchy. Explicitly clear conditional
        // rows so a zero count or a resize cannot leave an earlier expiry on screen.
        for (int id : new int[]{R.id.widget_source_icon, R.id.widget_label, R.id.widget_value,
                R.id.widget_reset, R.id.widget_reset_count, R.id.widget_reset_expiry,
                R.id.widget_secondary_label, R.id.widget_secondary_value, R.id.widget_secondary_reset,
                R.id.widget_status, R.id.widget_progress, R.id.widget_secondary_progress,
                R.id.widget_refresh_spinner, R.id.widget_refresh_spinner_dark,
                R.id.widget_reveal, R.id.widget_secondary_reveal})
            views.setViewVisibility(id, View.GONE);
        // Selecting the empty child clears a prior fill animation on host reapply.
        // These flippers never auto-start or repeat; idle overlays remain GONE.
        for (int id : new int[]{R.id.widget_reveal_body, R.id.widget_reveal_cap,
                R.id.widget_secondary_reveal_body, R.id.widget_secondary_reveal_cap})
            views.setDisplayedChild(id, 0);
        if (!state.configured) unconfigured();
        else if (height < 110) compact();
        else if (width >= 250 && state.fiveHourRemaining != null) detail();
        else weeklyDetail();
        float refreshY = height < 110 ? (height - 44) / 2f : 5;
        box(R.id.widget_refresh, width - 49, Math.max(0, refreshY), 44, Math.min(44, height));
        views.setInt(R.id.widget_refresh, "setBackgroundResource",
                dark ? R.drawable.widget_refresh_background_dark : R.drawable.widget_refresh_background);
        // Setting a background may replace ImageButton's XML padding. Set it last
        // so the idle glyph has exactly the spinner's 22dp drawing area.
        int refreshPadding = Math.round(11 * density);
        views.setViewPadding(R.id.widget_refresh, refreshPadding, refreshPadding,
                refreshPadding, refreshPadding);
        boolean spinning = refreshing && animateRefresh;
        views.setImageViewResource(R.id.widget_refresh, spinning ? android.R.color.transparent : refreshing
                ? (dark ? R.drawable.ic_widget_wait_dark : R.drawable.ic_widget_wait)
                : (dark ? R.drawable.ic_widget_refresh_dark : R.drawable.ic_widget_refresh));
        if (spinning) {
            // The launcher animates this vector locally. Its finite 27-second duration
            // cannot leave an endless spinner if the app process disappears mid-read.
            int spinner = dark ? R.id.widget_refresh_spinner_dark : R.id.widget_refresh_spinner;
            box(spinner, width - 49, Math.max(0, refreshY), 44, Math.min(44, height));
        }
        views.setContentDescription(R.id.widget_refresh, refreshing ? "正在刷新额度" : "在桌面刷新额度");
        views.setBoolean(R.id.widget_refresh, "setEnabled", !refreshing);
    }

    /** Stateless native layout, also exposed for inflation and visual verification. */
    public static RemoteViews remoteViews(Context context, int widthDp, int heightDp,
                                          WidgetState state, boolean dark, boolean refreshing) {
        return remoteViews(context, widthDp, heightDp, state, dark, refreshing,
                ValueAnimator.areAnimatorsEnabled());
    }

    /** Explicit motion choice also allows verification of the reduced-motion fallback. */
    public static RemoteViews remoteViews(Context context, int widthDp, int heightDp,
                                          WidgetState state, boolean dark, boolean refreshing,
                                          boolean animateRefresh) {
        return new WidgetRenderer(context, widthDp, heightDp, state, dark, refreshing,
                animateRefresh, false).views;
    }

    /** The launcher draws the short reveal locally at its native display cadence. */
    public static RemoteViews remoteViews(Context context, int widthDp, int heightDp,
                                          WidgetState state, boolean dark, boolean refreshing,
                                          boolean animateRefresh, boolean reveal) {
        return new WidgetRenderer(context, widthDp, heightDp, state, dark, refreshing,
                animateRefresh, reveal).views;
    }

    /** Settings-only preview. The actual home widget never transports or scales this bitmap. */
    public static Bitmap render(Context context, int widthDp, int heightDp, WidgetState state, boolean dark) {
        int width = Math.max(110, Math.min(700, widthDp));
        int height = Math.max(40, Math.min(300, heightDp));
        float density = context.getResources().getDisplayMetrics().density;
        int pixelsWide = Math.max(1, Math.round(width * density));
        int pixelsHigh = Math.max(1, Math.round(height * density));
        View preview = remoteViews(context, width, height, state, dark, false)
                .apply(context, new FrameLayout(context));
        preview.measure(View.MeasureSpec.makeMeasureSpec(pixelsWide, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixelsHigh, View.MeasureSpec.EXACTLY));
        preview.layout(0, 0, pixelsWide, pixelsHigh);
        Bitmap bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        preview.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void compact() {
        float pad = width < 180 ? 11 : 15;
        float contentWidth = width - pad - 58;
        boolean tiny = height < 64;
        float top = tiny ? Math.max(3, (height - 36) / 2f) : (height - 58) / 2f;
        float headingHeight = tiny ? 21 : 26;
        float valueWidth = Math.min(width < 180 ? 51 : 88, contentWidth * .55f);
        if (width >= 160) {
            float labelWidth = contentWidth - valueWidth - 3;
            if (width >= 220) {
                String title = width >= 300 ? "Codex 额度 · 每周剩余" : width >= 240 ? "Codex 额度" : "Codex";
                sourceLabel(title, pad, top + (tiny ? 2 : 4), labelWidth,
                        headingHeight - (tiny ? 2 : 4));
            } else {
                label(R.id.widget_label, "周余量", pad, top + (tiny ? 2 : 4),
                        labelWidth, headingHeight - (tiny ? 2 : 4), secondary);
            }
            amount(R.id.widget_value, state.weeklyRemaining, pad + contentWidth - valueWidth,
                    top, valueWidth, headingHeight, true);
        } else {
            amount(R.id.widget_value, state.weeklyRemaining, pad, top, contentWidth, headingHeight, false);
        }
        float barY = tiny ? top + 24 : top + 30;
        bar(R.id.widget_progress, pad, barY, contentWidth, tiny ? 6 : 9, state.weeklyRemaining);
        if (!tiny) {
            String footer = width >= 270 ? compactFooter() : shortStatus();
            // A complete CJK line needs its full ascent/descent, including font fallback.
            // Keep the capsule in place and use the space immediately below it for a 16dp line.
            label(R.id.widget_status, footer, pad, barY + 12, contentWidth, 16, muted);
        }
    }

    private void weeklyDetail() {
        float pad = width < 180 ? 14 : 18;
        float contentWidth = width - pad * 2;
        boolean small = smallDetail();
        float top = detailTop(small);
        DetailMetrics layout = detailMetrics(top, small);
        contentOffsetY = centeredDetailOffset(top, layout);
        if (width >= 220) sourceLabel("Codex 额度 · 每周剩余", pad, top, width - pad - 54, 18);
        else label(R.id.widget_label, "每周剩余", pad, top, width - pad - 54, 18, secondary);
        float valueTop = layout.valueTop;
        float valueHeight = layout.valueHeight;
        amount(R.id.widget_value, state.weeklyRemaining, pad, valueTop,
                width < 180 ? contentWidth : contentWidth * .65f, valueHeight, false);
        float barY = valueTop + valueHeight + layout.barGap;
        float barHeight = layout.barHeight;
        bar(R.id.widget_progress, pad, barY, contentWidth, barHeight, state.weeklyRemaining);
        float resetY = barY + barHeight + layout.resetGap;
        label(R.id.widget_reset, reset(state.weeklyResetAt), pad, resetY, contentWidth, 16, secondary);
        detailFooter(pad, resetY, contentWidth);
    }

    private void detail() {
        float pad = width < 320 ? 16 : 20;
        float gap = width < 320 ? 20 : 28;
        float column = (width - pad * 2 - gap) / 2f;
        float secondX = pad + column + gap;
        boolean small = smallDetail();
        float top = detailTop(small);
        DetailMetrics layout = detailMetrics(top, small);
        contentOffsetY = centeredDetailOffset(top, layout);
        float valueTop = layout.valueTop;
        float valueHeight = layout.valueHeight;
        sourceLabel("Codex 额度 · 每周", pad, top, column, 18);
        label(R.id.widget_secondary_label, "5 小时剩余", secondX, top, width - secondX - 49, 18, secondary);
        amount(R.id.widget_value, state.weeklyRemaining, pad, valueTop, column, valueHeight, false);
        amount(R.id.widget_secondary_value, state.fiveHourRemaining, secondX, valueTop, column, valueHeight, false);
        float barY = valueTop + valueHeight + layout.barGap;
        float barHeight = layout.barHeight;
        bar(R.id.widget_progress, pad, barY, column, barHeight, state.weeklyRemaining);
        bar(R.id.widget_secondary_progress, secondX, barY, column, barHeight, state.fiveHourRemaining);
        float resetY = barY + barHeight + layout.resetGap;
        label(R.id.widget_reset, reset(state.weeklyResetAt), pad, resetY, column, 16, secondary);
        label(R.id.widget_secondary_reset, reset(state.fiveHourResetAt), secondX, resetY, column, 16, secondary);
        // Reset credits are shared by the account, so show one count below both windows.
        detailFooter(pad, resetY, width - pad * 2);
    }

    private static final class DetailMetrics {
        float valueTop, valueHeight, barGap, barHeight, resetGap;
    }

    private boolean showResetExpiry() {
        return state.availableResetCount != null && state.availableResetCount > 0;
    }

    private boolean separateDetailStatus() {
        return height >= (showResetExpiry() ? 196 : 160);
    }

    private boolean smallDetail() {
        return height < 134 || (showResetExpiry() && height < 160);
    }

    private float detailTop(boolean small) {
        return height > 200 ? 22 : showResetExpiry() && height < 134 ? 4 : small ? 10 : 16;
    }

    private float centeredDetailOffset(float top, DetailMetrics layout) {
        float resetY = layout.valueTop + layout.valueHeight + layout.barGap
                + layout.barHeight + layout.resetGap;
        float lastBottom = separateDetailStatus() ? height - 9
                : resetY + (showResetExpiry() ? 52 : 34);
        // Translate the existing content as one group: equal outer padding with
        // identical text sizes, progress bars, and spacing between every row.
        return (height - lastBottom - top) / 2f;
    }

    private DetailMetrics detailMetrics(float top, boolean small) {
        DetailMetrics layout = new DetailMetrics();
        layout.valueTop = top + (small ? 18 : 22);
        boolean tight = showResetExpiry() && height < 134;
        layout.barGap = tight ? 2 : small ? 4 : 10;
        layout.resetGap = tight ? 2 : small ? 6 : 9;
        layout.barHeight = tight ? 7 : small ? 9 : 11;
        // Every footer retains a complete 16dp line. On typical two-row cards the
        // expiry uses the former update row, keeping the main content almost unchanged.
        float reserved = separateDetailStatus() ? (showResetExpiry() ? 79 : 61)
                : (showResetExpiry() ? 59 : 42);
        float resetLimit = height - reserved;
        float afterValue = layout.barGap + layout.barHeight + layout.resetGap;
        layout.valueHeight = Math.min(small ? 32 : Math.min(63, 36 + (height - 134) * .25f),
                resetLimit - layout.valueTop - afterValue);
        if (height >= 142) {
            float spare = resetLimit - layout.valueTop - layout.valueHeight - afterValue;
            layout.valueTop += Math.min(10, Math.max(0, spare));
        }
        return layout;
    }

    private void detailFooter(float pad, float resetY, float contentWidth) {
        String count = state.availableResetCount == null ? "可用重置 —"
                : "可用重置 " + state.availableResetCount + " 次";
        if (!separateDetailStatus()) {
            // Keep status feedback when the expiry occupies the separate update line.
            if (refreshing) count += " · 刷新中";
            else if (state.demo) count += " · 演示";
            else if (old()) count += " · 旧数据";
            else if (height >= 160 && width >= 270 && state.updatedAt > 0)
                count += " · " + date(state.updatedAt, "HH:mm") + " 更新";
        }
        label(R.id.widget_reset_count, count, pad, resetY + 18, contentWidth, 16, secondary);
        if (showResetExpiry())
            label(R.id.widget_reset_expiry, resetExpiry(state, now), pad, resetY + 36,
                    contentWidth, 16, muted);
        if (separateDetailStatus())
            label(R.id.widget_status, status(), pad, height - 25, contentWidth, 16, muted);
    }

    /** Shared with the widget's accessibility description; all dates use Beijing time. */
    static String resetExpiry(WidgetState snapshot, long now) {
        if (snapshot.nextResetCreditExpiresAt == null) return "到期时间未提供";
        if (snapshot.nextResetCreditExpiresAt <= now) return "到期信息待刷新";
        SimpleDateFormat format = new SimpleDateFormat("M/d HH:mm", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return "最近到期 " + format.format(new Date(snapshot.nextResetCreditExpiresAt * 1000L));
    }

    private void unconfigured() {
        float pad = width < 180 ? 12 : 18;
        float available = width - pad - 57;
        float center = height / 2f;
        label(R.id.widget_label, "余量 · 待登录", pad, center - (height < 58 ? 10 : 20), available, 20, ink);
        if (height >= 58) label(R.id.widget_status, "点按卡片登录 ChatGPT", pad, center + 5, available, 15, secondary);
    }

    private void sourceLabel(String text, float x, float y, float available, float lineHeight) {
        float icon = 16;
        box(R.id.widget_source_icon, x, y + (lineHeight - icon) / 2f, icon, icon);
        label(R.id.widget_label, text, x + icon + 6, y, available - icon - 6, lineHeight, secondary);
    }

    private void box(int id, float x, float y, float width, float height) {
        views.setViewVisibility(id, View.VISIBLE);
        views.setViewLayoutWidth(id, Math.max(1, width), TypedValue.COMPLEX_UNIT_DIP);
        views.setViewLayoutHeight(id, Math.max(1, height), TypedValue.COMPLEX_UNIT_DIP);
        views.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, x, TypedValue.COMPLEX_UNIT_DIP);
        views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP,
                y + (id == R.id.widget_refresh || id == R.id.widget_refresh_spinner
                        || id == R.id.widget_refresh_spinner_dark ? 0 : contentOffsetY),
                TypedValue.COMPLEX_UNIT_DIP);
    }

    private void label(int id, String value, float x, float y, float width, float height, int color) {
        box(id, x, y, width, height);
        views.setTextViewText(id, value);
        views.setTextColor(id, color);
    }

    private void amount(int id, Double percent, float x, float y, float width, float height, boolean alignRight) {
        box(id, x, y, width, height);
        views.setTextColor(id, ink);
        views.setInt(id, "setGravity", (alignRight ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        if (percent == null || !Double.isFinite(percent)) {
            views.setTextViewText(id, "—");
            views.setContentDescription(id, "额度暂未提供");
            return;
        }
        double bounded = Math.max(0, Math.min(100, percent));
        String number = bounded > 0 && bounded < .1 ? "<0.1"
                : bounded > 99.9 && bounded < 100 ? ">99.9"
                : bounded == Math.rint(bounded) ? String.format(Locale.ROOT, "%.0f", bounded)
                : String.format(Locale.ROOT, "%.1f", bounded);
        SpannableString formatted = new SpannableString(number + "%");
        formatted.setSpan(new RelativeSizeSpan(.48f), number.length(), formatted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        views.setTextViewText(id, formatted);
        views.setContentDescription(id, "剩余百分之" + number);
    }

    private void bar(int id, float x, float y, float width, float height, Double percent) {
        box(id, x, y, width, height);
        int progress = percent == null || !Double.isFinite(percent) ? 0
                : (int)Math.round(Math.max(0, Math.min(100, percent)) * 100);
        int widthPx = Math.max(1, Math.round(width * density));
        int heightPx = Math.max(1, Math.round(height * density));
        // Match ScaleDrawable's physical-pixel rounding, avoiding a final 1px jump.
        int fillPx = widthPx - (int) (widthPx * (10000 - progress) / 10000f);
        boolean animate = reveal && progress > 0 && fillPx > heightPx;
        views.setProgressBar(id, 10000, animate ? 0 : progress, false);
        views.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(accent));
        views.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf(track));
        if (animate) revealBar(id == R.id.widget_secondary_progress, x, y, fillPx, heightPx);
    }

    /** Only the rectangular middle scales; both end caps keep their original radius. */
    private void revealBar(boolean secondaryBar, float x, float y, int fillPx, int heightPx) {
        int overlay = secondaryBar ? R.id.widget_secondary_reveal : R.id.widget_reveal;
        int left = secondaryBar ? R.id.widget_secondary_reveal_left : R.id.widget_reveal_left;
        int body = secondaryBar ? R.id.widget_secondary_reveal_body : R.id.widget_reveal_body;
        int bodyFill = secondaryBar ? R.id.widget_secondary_reveal_body_fill : R.id.widget_reveal_body_fill;
        int cap = secondaryBar ? R.id.widget_secondary_reveal_cap : R.id.widget_reveal_cap;
        int capFill = secondaryBar ? R.id.widget_secondary_reveal_cap_fill : R.id.widget_reveal_cap_fill;
        box(overlay, x, y, fillPx / density, heightPx / density);
        // Sizes are in physical pixels, shared by both animations and final progress.
        sizePx(overlay, fillPx, heightPx);
        sizePx(left, heightPx, heightPx);
        sizePx(body, fillPx - heightPx, heightPx);
        views.setViewLayoutMargin(body, RemoteViews.MARGIN_LEFT, heightPx / 2f,
                TypedValue.COMPLEX_UNIT_PX);
        sizePx(cap, fillPx, heightPx);
        sizePx(capFill, heightPx, heightPx);
        int circle = dark ? R.drawable.widget_reveal_circle_dark : R.drawable.widget_reveal_circle;
        views.setImageViewResource(left, circle);
        views.setImageViewResource(capFill, circle);
        views.setInt(bodyFill, "setBackgroundColor", accent);
        views.setDisplayedChild(body, 1);
        views.setDisplayedChild(cap, 1);
    }

    private void sizePx(int id, int width, int height) {
        views.setViewLayoutWidth(id, width, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutHeight(id, height, TypedValue.COMPLEX_UNIT_PX);
    }

    private boolean expired(long reset) { return reset > 0 && now >= reset; }
    private boolean old() {
        return (state.weeklyRemaining != null || state.fiveHourRemaining != null)
                && (state.stale || (state.error != null && !state.error.isEmpty())
                || expired(state.weeklyResetAt) || expired(state.fiveHourResetAt));
    }
    private String status() {
        if (refreshing) return "正在刷新…";
        if (state.demo) return old() ? "演示 · 旧数据" : "演示数据";
        if (old()) return "旧数据 · 点击刷新";
        if (state.weeklyRemaining == null && state.fiveHourRemaining == null
                && state.error != null && !state.error.isEmpty()) return "读取失败 · 点击重试";
        if (state.updatedAt <= 0) return "点击右侧刷新额度";
        return date(state.updatedAt, "M/d HH:mm") + " 更新 · 北京时间";
    }
    private String shortStatus() {
        if (refreshing || old() || state.demo || state.updatedAt <= 0) return status();
        return date(state.updatedAt, "HH:mm") + " 更新";
    }
    private String compactFooter() {
        if (refreshing || old() || state.demo || state.updatedAt <= 0) return status();
        return reset(state.weeklyResetAt) + "  ·  " + date(state.updatedAt, "HH:mm") + " 更新";
    }
    private String date(long seconds, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(seconds * 1000L));
    }
    private String reset(long seconds) {
        if (seconds <= 0) return "重置时间未提供";
        if (expired(seconds)) return "已到期 · 待刷新";
        boolean today = date(seconds, "yyyyMMdd").equals(date(now, "yyyyMMdd"));
        return (today ? "今天 " : date(seconds, "M/d ")) + date(seconds, "HH:mm") + " 重置";
    }
}
