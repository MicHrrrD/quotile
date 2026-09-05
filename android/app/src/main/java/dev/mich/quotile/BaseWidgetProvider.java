package dev.mich.quotile;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
public abstract class BaseWidgetProvider extends AppWidgetProvider {
    static final String REFRESH = "dev.mich.quotile.REFRESH";
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH.equals(intent.getAction())) {
            Schedule.requestNow(context);
        }
    }
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        for (int id : ids) WidgetUpdate.update(c, manager, id);
        Schedule.ensure(c); Schedule.requestNow(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        WidgetUpdate.update(c, m, id);
    }
    @Override public void onEnabled(Context c) { Schedule.ensure(c); }
    @Override public void onDisabled(Context c) { Schedule.ensure(c); }
    @Override public void onDeleted(Context c, int[] ids) { Schedule.ensure(c); }
}
