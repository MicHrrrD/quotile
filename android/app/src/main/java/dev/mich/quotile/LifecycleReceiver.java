package dev.mich.quotile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class LifecycleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        WidgetUpdate.updateAll(c); Schedule.ensure(c); Schedule.requestNow(c);
    }
}
