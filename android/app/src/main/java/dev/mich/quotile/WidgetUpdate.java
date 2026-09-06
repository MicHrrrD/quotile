package dev.mich.quotile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class WidgetUpdate {
    private WidgetUpdate() {}
    public static boolean hasWidgets(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, SlimWidgetProvider.class)).length > 0
                || m.getAppWidgetIds(new ComponentName(c, DetailWidgetProvider.class)).length > 0;
    }
    public static void updateAll(Context c) {
        WidgetMotion.cancel();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, SlimWidgetProvider.class))) update(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, DetailWidgetProvider.class))) update(c, m, id);
    }
    /** Prepare layout and click actions once; animation frames only clone and change fill levels. */
    static ArrayList<Prepared> prepareMotion(Context c, WidgetState state) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        ArrayList<Prepared> frames = new ArrayList<>();
        for (Class<?> provider : new Class<?>[]{SlimWidgetProvider.class, DetailWidgetProvider.class})
            for (int id : m.getAppWidgetIds(new ComponentName(c, provider))) {
                Prepared prepared = prepare(c, m, id, state);
                if (prepared != null) frames.add(prepared);
            }
        return frames;
    }
    public static void update(Context c, AppWidgetManager m, int id) {
        WidgetMotion.cancel();
        Prepared prepared = prepare(c, m, id, new QuotaStore(c).state());
        if (prepared != null) prepared.update(1f);
    }
    private static Prepared prepare(Context c, AppWidgetManager m, int id, WidgetState state) {
        AppWidgetProviderInfo info = m.getAppWidgetInfo(id);
        if (info == null) return null;
        boolean detail = info.provider.getClassName().equals(DetailWidgetProvider.class.getName());
        QuotaStore store = new QuotaStore(c);
        boolean dark = store.theme().equals("dark") || (store.theme().equals("system")
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        Bundle options = m.getAppWidgetOptions(id);
        ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        try {
            if (sizes != null && !sizes.isEmpty()) {
                Map<SizeF, RemoteViews> views = new LinkedHashMap<>();
                for (SizeF size : sizes) {
                    if (!Float.isFinite(size.getWidth()) || !Float.isFinite(size.getHeight()) || size.getWidth() <= 0 || size.getHeight() <= 0) continue;
                    if (views.size() >= 4) break; // Foldables normally report four exact size variants.
                    views.put(size, view(c, id, info.provider, Math.round(size.getWidth()), Math.round(size.getHeight()), state, dark));
                }
                if (!views.isEmpty()) return new Prepared(m, id, state, views, null, null);
            }
            // Launchers that don't supply API 31 exact sizes still get portrait/landscape variants.
            int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340);
            int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
            int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, detail ? 156 : 72);
            int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
            RemoteViews portrait = view(c, id, info.provider, minW, maxH, state, dark);
            RemoteViews landscape = view(c, id, info.provider, maxW, minH, state, dark);
            return new Prepared(m, id, state, null, landscape, portrait);
        } catch (IllegalArgumentException memoryOrLauncher) {
            // Fall back for launchers with anomalous dimension reports.
            RemoteViews fallback = view(c, id, info.provider, 300, detail ? 140 : 64, state, dark);
            return new Prepared(m, id, state, null, fallback, fallback);
        }
    }
    static final class Prepared {
        private final AppWidgetManager manager;
        private final int id;
        private final WidgetState state;
        private final Map<SizeF, RemoteViews> sizes;
        private final RemoteViews landscape, portrait;

        Prepared(AppWidgetManager manager, int id, WidgetState state, Map<SizeF, RemoteViews> sizes,
                 RemoteViews landscape, RemoteViews portrait) {
            this.manager = manager;
            this.id = id;
            this.state = state;
            this.sizes = sizes;
            this.landscape = landscape;
            this.portrait = portrait;
        }
        void update(float fraction) {
            if (sizes != null) {
                Map<SizeF, RemoteViews> frame = new LinkedHashMap<>();
                for (Map.Entry<SizeF, RemoteViews> size : sizes.entrySet())
                    frame.put(size.getKey(), child(size.getValue(), fraction));
                manager.updateAppWidget(id, new RemoteViews(frame));
            } else {
                manager.updateAppWidget(id, new RemoteViews(child(landscape, fraction), child(portrait, fraction)));
            }
        }
        private RemoteViews child(RemoteViews template, float fraction) {
            RemoteViews frame = template.clone();
            WidgetMotion.applyProgress(frame, state, fraction);
            return frame;
        }
    }
    private static RemoteViews view(Context c, int id, ComponentName provider, int width, int height, WidgetState state, boolean dark) {
        width = Math.max(110, Math.min(width, 700));
        height = Math.max(40, Math.min(height, 300));
        RemoteViews rv = WidgetRenderer.remoteViews(c, width, height, state, dark, QuotaSync.isRunning());
        rv.setContentDescription(R.id.widget_root, description(state, height >= 110));
        Intent open = new Intent(c, MainActivity.class);
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent refresh = new Intent(c, WidgetRefreshReceiver.class).setAction(WidgetRefreshReceiver.ACTION_REFRESH);
        refresh.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent refreshAction = PendingIntent.getBroadcast(c, id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_refresh, refreshAction);
        return rv;
    }
    private static String description(WidgetState s, boolean showResets) {
        if (!s.configured) return "余量，待连接，点按打开设置";
        String status = QuotaSync.isRunning() ? "正在刷新。" : s.demo ? "演示数据。" : (s.stale ? "旧数据，待手动刷新。" : "上次读取的额度。");
        return "Codex 额度。" + status + "每周剩余" + amount(s.weeklyRemaining) + "，五小时剩余" + amount(s.fiveHourRemaining)
                + (showResets ? "。可用重置" + (s.availableResetCount == null ? "次数未提供" : s.availableResetCount + "次") : "")
                + (showResets && s.availableResetCount != null && s.availableResetCount > 0
                        ? "。" + WidgetRenderer.resetExpiry(s, System.currentTimeMillis() / 1000L) + "，北京时间" : "")
                + "。点按打开设置，右侧按钮刷新。";
    }
    private static String amount(Double percent) {
        return percent == null ? "未提供" : String.format(Locale.CHINA, "百分之%.0f", percent);
    }
}
