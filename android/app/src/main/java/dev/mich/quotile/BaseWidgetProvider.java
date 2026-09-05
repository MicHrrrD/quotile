package dev.mich.quotile;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Launcher events only draw saved data; none can start an account request. */
public abstract class BaseWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        new QuotaStore(c).migrateManualMode();
        for (int id : ids) WidgetUpdate.update(c, manager, id);
        Schedule.apply(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        WidgetUpdate.update(c, m, id);
    }
}
