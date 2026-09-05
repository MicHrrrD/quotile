package dev.mich.quotile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Density-bounded, launcher-size-aware artwork. Layout dimensions below are dp. */
public final class WidgetRenderer {
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface NUMBER = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Canvas canvas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final float width, height;
    private final WidgetState state;
    private final long now = System.currentTimeMillis() / 1000L;
    private final int ink, secondary, muted, track, accent, warning, border;

    private WidgetRenderer(Canvas c, float w, float h, WidgetState s, boolean dark) {
        canvas = c; width = w; height = h; state = s;
        ink = Color.parseColor(dark ? "#F0F2EF" : "#25362F");
        secondary = Color.parseColor(dark ? "#BAC4BD" : "#68786F");
        muted = Color.parseColor(dark ? "#99A69E" : "#76867D");
        track = Color.parseColor(dark ? "#35403A" : "#E1E9E1");
        accent = Color.parseColor(dark ? "#A4D4B9" : "#609A7D");
        warning = Color.parseColor(dark ? "#E3BD83" : "#976B39");
        border = Color.parseColor(dark ? "#39433D" : "#E0E7DF");
        paint.setColor(Color.parseColor(dark ? "#242D28" : "#F6F8F2"));
        float radius = Math.min(22f, height * .29f);
        canvas.drawRoundRect(new RectF(.5f, .5f, w - .5f, h - .5f), radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.7f); paint.setColor(border);
        canvas.drawRoundRect(new RectF(.5f, .5f, w - .5f, h - .5f), radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    public static Bitmap render(Context context, int widthDp, int heightDp, WidgetState state, boolean dark) {
        int w = Math.max(110, Math.min(700, widthDp));
        int h = Math.max(40, Math.min(300, heightDp));
        float scale = Math.max(1f, Math.min(1.5f, context.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(Math.round(w * scale), Math.round(h * scale), Bitmap.Config.ARGB_8888);
        // ImageView scales this bitmap into the exact supplied bounds, without density auto-scaling.
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale);
        WidgetRenderer r = new WidgetRenderer(canvas, w, h, state == null ? new WidgetState() : state, dark);
        if (!r.state.configured) r.unconfigured();
        else if (w >= 250 && h >= 116) r.detail();
        else if (h >= 116) r.narrow();
        else r.compact();
        r.refresh();
        return bitmap;
    }

    private boolean expired(long reset) { return reset > 0 && now >= reset; }
    private boolean old() {
        return (state.weeklyRemaining != null || state.fiveHourRemaining != null)
                && (state.stale || (state.error != null && !state.error.isEmpty())
                || expired(state.weeklyResetAt) || expired(state.fiveHourResetAt));
    }
    private String status() {
        if (state.demo) return old() ? "演示 · 旧数据" : "演示数据";
        if (old()) return "旧数据 · 待刷新";
        if (state.weeklyRemaining == null && state.fiveHourRemaining == null
                && state.error != null && !state.error.isEmpty()) return "暂未获取额度";
        if (state.updatedAt <= 0) return "点击刷新读取";
        return date(state.updatedAt, "M/d HH:mm") + " 读取";
    }
    private String date(long seconds, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(seconds * 1000L));
    }
    private String reset(long seconds, boolean shortForm) {
        if (seconds <= 0) return shortForm ? "重置未提供" : "未提供重置时间";
        if (expired(seconds)) return shortForm ? "已到期 · 待刷新" : "已到重置时间 · 待刷新";
        boolean today = date(seconds, "yyyyMMdd").equals(date(now, "yyyyMMdd"));
        return (today ? "今天 " : date(seconds, shortForm ? "M/d " : "M月d日 "))
                + date(seconds, "HH:mm") + " 重置";
    }
    private void text(String value, float x, float baseline, float size, int color, float maxWidth, Typeface face) {
        if (value == null || maxWidth <= 0 || baseline < size * .7f || baseline > height - 2) return;
        paint.setTypeface(face); paint.setTextSize(size); paint.setColor(color);
        String out = value.replace('\n', ' ').replace('\r', ' ');
        if (paint.measureText(out) > maxWidth) {
            float ellipsis = paint.measureText("…");
            int count = paint.breakText(out, true, Math.max(0, maxWidth - ellipsis), null);
            out = count > 0 ? out.substring(0, count) + "…" : "";
        }
        canvas.drawText(out, x, baseline, paint);
    }
    private void value(Double amount, float x, float baseline, float desiredSize, float maxWidth) {
        if (amount == null || !Double.isFinite(amount)) {
            text("未提供", x, baseline - 3, Math.min(18, desiredSize * .53f), secondary, maxWidth, REGULAR); return;
        }
        double bounded = Math.max(0, Math.min(100, amount));
        // Preserve the zero/full distinction at the boundaries.
        String number = bounded > 0 && bounded < .1 ? "<0.1"
                : bounded > 99.9 && bounded < 100 ? ">99.9"
                : bounded == Math.rint(bounded) ? String.format(Locale.ROOT, "%.0f", bounded)
                : String.format(Locale.ROOT, "%.1f", bounded);
        float size = desiredSize;
        paint.setTypeface(NUMBER); paint.setTextSize(size);
        float digitsWidth = paint.measureText(number);
        paint.setTextSize(size * .43f); float suffixWidth = paint.measureText("%");
        if (digitsWidth + suffixWidth + 3 > maxWidth) size *= maxWidth / (digitsWidth + suffixWidth + 3);
        paint.setTextSize(size); paint.setTypeface(NUMBER); paint.setColor(ink);
        canvas.drawText(number, x, baseline, paint);
        float end = x + paint.measureText(number) + 2;
        paint.setTextSize(size * .43f); paint.setColor(secondary);
        canvas.drawText("%", end, baseline - size * .06f, paint);
    }
    private void bar(float x, float y, float w, Double amount) {
        if (w <= 0) return;
        paint.setColor(track); canvas.drawRoundRect(new RectF(x, y, x + w, y + 3), 1.5f, 1.5f, paint);
        if (amount == null || !Double.isFinite(amount)) return;
        float filled = w * (float)Math.max(0, Math.min(100, amount)) / 100f;
        if (filled <= 0) return;
        paint.setColor(old() ? warning : accent);
        canvas.drawRoundRect(new RectF(x, y, x + filled, y + 3), 1.5f, 1.5f, paint);
    }
    private void compact() {
        float pad = width < 200 ? 10 : 14;
        float right = width - 48, available = right - pad;
        boolean tiny = height < 56;
        float labelY = tiny ? 12 : height * .5f - 15;
        float numberY = tiny ? 32 : height * .5f + 15;
        float numberSize = tiny ? 24 : Math.min(35, height * .48f);
        String label = width < 200 ? (state.demo ? "演示·周余量" : old() ? "旧·周余量" : "周余量") : "每周剩余";
        // At the smallest size the status takes precedence over the descriptive label.
        if (available < 65) label = state.demo ? (old() ? "演示·旧" : "演示") : old() ? "旧数据" : "周余量";
        text(label, pad, labelY, 10, old() ? warning : secondary, available, MEDIUM);
        float primaryWidth = Math.min(98, available);
        value(state.weeklyRemaining, pad, numberY, numberSize, primaryWidth);
        if (width >= 250) {
            float x = pad + 110, infoWidth = right - x;
            text(reset(state.weeklyResetAt, true), x, tiny ? 17 : numberY - 18, tiny ? 10 : 11, secondary, infoWidth, REGULAR);
            text(status(), x, tiny ? 31 : numberY - 1, 10, old() ? warning : muted, infoWidth, REGULAR);
        } else if (width >= 175) {
            float x = pad + 76;
            text(state.demo ? "演示" : old() ? "旧数据" : "剩余", x, numberY - 2, 10,
                    old() ? warning : muted, right - x, REGULAR);
        }
        bar(pad, height - (tiny ? 4 : 8), available, state.weeklyRemaining);
    }
    private void detail() {
        if (state.fiveHourRemaining == null) { weeklyDetail(); return; }
        float pad = width < 320 ? 14 : 18;
        float top = height < 135 ? 12 : 18;
        float gap = width < 320 ? 22 : 28;
        float col = (width - pad * 2 - gap) / 2;
        float right = pad + col + gap;
        float numberY = Math.min(height - 50, top + (height >= 180 ? 72 : 60));
        float numberSize = height < 135 ? 38 : height >= 180 ? 52 : 44;
        text("每周剩余", pad, top + 11, 11, secondary, col, MEDIUM);
        text("5小时剩余", right, top + 11, 11, secondary, width - 46 - right, MEDIUM);
        value(state.weeklyRemaining, pad, numberY, numberSize, col);
        value(state.fiveHourRemaining, right, numberY, numberSize, col);
        float barY = numberY + 11;
        bar(pad, barY, col, state.weeklyRemaining); bar(right, barY, col, state.fiveHourRemaining);
        float resetY = barY + 19;
        text(reset(state.weeklyResetAt, true), pad, resetY, 10, secondary, col, REGULAR);
        text(reset(state.fiveHourResetAt, true), right, resetY, 10, secondary, col, REGULAR);
        float footerY = height - Math.max(8, top * .65f);
        text(status(), pad, footerY, 10, old() ? warning : muted, width * .60f - pad, REGULAR);
        String caption = state.plan == null || state.plan.trim().isEmpty() ? "北京时间" : state.plan + " · 北京时间";
        paint.setTypeface(REGULAR); paint.setTextSize(9);
        float footerWidth = Math.min(width * .36f, paint.measureText(caption));
        text(caption, width - pad - footerWidth, footerY, 9, muted, footerWidth, REGULAR);
    }
    /** Use the larger card for the weekly window when the account has no 5h bucket. */
    private void weeklyDetail() {
        float pad = width < 320 ? 14 : 18;
        float numberY = Math.min(height - 42, height * .5f + 12);
        float numberSize = height < 135 ? 38 : 50;
        float infoX = width * .5f;
        text("每周剩余", pad, 26, 11, secondary, width - pad - 48, MEDIUM);
        value(state.weeklyRemaining, pad, numberY, numberSize, width * .43f - pad);
        text(reset(state.weeklyResetAt, true), infoX, numberY - 19, 11, secondary, width - pad - infoX, REGULAR);
        text(status(), infoX, numberY - 1, 10, old() ? warning : muted, width - pad - infoX, REGULAR);
        bar(pad, height - 27, width - pad * 2, state.weeklyRemaining);
        String caption = state.plan == null || state.plan.trim().isEmpty() ? "北京时间" : state.plan + " · 北京时间";
        text(caption, pad, height - 10, 9, muted, width - pad * 2, REGULAR);
    }
    private void narrow() {
        float pad = width < 160 ? 12 : 16;
        float content = width - pad * 2;
        text("每周剩余", pad, 26, 11, secondary, width - 46 - pad, MEDIUM);
        float numberY = height < 145 ? 64 : 74;
        value(state.weeklyRemaining, pad, numberY, width < 160 ? 36 : 44, content);
        bar(pad, numberY + 12, content, state.weeklyRemaining);
        if (height >= 142) text(reset(state.weeklyResetAt, true), pad, numberY + 33, 10, secondary, content, REGULAR);
        text(status(), pad, height - 12, 10, old() ? warning : muted, content, REGULAR);
    }
    private void unconfigured() {
        float pad = width < 180 ? 10 : 16;
        float available = width - 48 - pad;
        if (height < 56) {
            text("待连接", pad, 24, 14, ink, available, MEDIUM);
        } else {
            float center = height * .5f;
            text("余量 · 待连接", pad, center - 3, width < 180 ? 12 : 16, ink, available, MEDIUM);
            text(width < 180 ? "点按设置" : "点按设置，连接用量来源", pad, center + 17, 10, secondary, available, REGULAR);
        }
    }
    private void refresh() {
        float cx = width - 22, cy = height < 116 ? height * .5f : 22;
        paint.setColor(track);
        canvas.drawCircle(cx, cy, 13, paint);
        paint.setColor(secondary); paint.setStrokeWidth(1.35f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(Paint.Style.STROKE);
        canvas.drawArc(new RectF(cx - 5, cy - 5, cx + 5, cy + 5), 35, 287, false, paint);
        Path arrow = new Path(); arrow.moveTo(cx + 1.3f, cy - 5.8f); arrow.lineTo(cx + 4.5f, cy - 4.1f); arrow.lineTo(cx + 4.6f, cy - 7.5f);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.BUTT);
    }
}
