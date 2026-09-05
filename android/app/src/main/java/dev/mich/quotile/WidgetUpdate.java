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
import android.util.TypedValue;
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
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, SlimWidgetProvider.class))) update(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, DetailWidgetProvider.class))) update(c, m, id);
    }
    public static void update(Context c, AppWidgetManager m, int id) {
        AppWidgetProviderInfo info = m.getAppWidgetInfo(id);
        if (info == null) return;
        boolean detail = info.provider.getClassName().equals(DetailWidgetProvider.class.getName());
        QuotaStore store = new QuotaStore(c);
        WidgetState state = store.state();
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
                if (!views.isEmpty()) { m.updateAppWidget(id, new RemoteViews(views)); return; }
            }
            // Launchers that don't supply API 31 exact sizes still get portrait/landscape variants.
            int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340);
            int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
            int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, detail ? 156 : 72);
            int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
            RemoteViews portrait = view(c, id, info.provider, minW, maxH, state, dark);
            RemoteViews landscape = view(c, id, info.provider, maxW, minH, state, dark);
            m.updateAppWidget(id, new RemoteViews(landscape, portrait));
        } catch (IllegalArgumentException memoryOrLauncher) {
            // Bound memory on launchers with anomalous dimension reports.
            m.updateAppWidget(id, view(c, id, info.provider, 300, detail ? 140 : 64, state, dark));
        }
    }
    private static RemoteViews view(Context c, int id, ComponentName provider, int width, int height, WidgetState state, boolean dark) {
        width = Math.max(110, Math.min(width, 700));
        height = Math.max(40, Math.min(height, 300));
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        rv.setImageViewBitmap(R.id.widget_image, WidgetRenderer.render(c, width, height, state, dark));
        rv.setContentDescription(R.id.widget_root, description(state));
        Intent open = new Intent(c, MainActivity.class);
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent refresh = new Intent(c, RefreshActivity.class).setAction(RefreshActivity.ACTION_USER_REFRESH);
        refresh.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent refreshAction = !state.configured
                ? PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(c, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_refresh, refreshAction);
        rv.setViewLayoutHeight(R.id.widget_refresh, height < 116 ? height : 44, TypedValue.COMPLEX_UNIT_DIP);
        return rv;
    }
    private static String description(WidgetState s) {
        if (!s.configured) return "余量，待连接，点按打开设置";
        String status = s.demo ? "演示数据。" : (s.stale ? "旧数据，待手动刷新。" : "上次读取的额度。");
        return status + "每周剩余" + amount(s.weeklyRemaining) + "，五小时剩余" + amount(s.fiveHourRemaining)
                + "。点按打开设置，右侧按钮刷新。";
    }
    private static String amount(Double percent) {
        return percent == null ? "未提供" : String.format(Locale.CHINA, "百分之%.0f", percent);
    }
}
