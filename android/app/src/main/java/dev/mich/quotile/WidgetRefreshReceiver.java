package dev.mich.quotile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** A single explicit home-screen tap; it never opens an activity or schedules another read. */
public final class WidgetRefreshReceiver extends BroadcastReceiver {
    public static final String ACTION_REFRESH = "dev.mich.quotile.REFRESH_WIDGET";
    private static volatile long lastCompletionAt;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REFRESH.equals(intent.getAction())) return;
        // Some hosts deliver repeated taps together after the previous async broadcast ends.
        // Coalesce that burst instead of treating its delayed second tap as another request.
        long completed = lastCompletionAt;
        if (completed > 0 && SystemClock.elapsedRealtime() - completed < 1000L) return;
        // The immutable widget PendingIntent does not use FLAG_RECEIVER_FOREGROUND. Android
        // permits this asynchronous broadcast about 30 seconds; the operation ends it by 27.
        PendingResult pending = goAsync();
        QuotaSync.refreshFromWidget(context.getApplicationContext(), () -> {
            lastCompletionAt = SystemClock.elapsedRealtime();
            if (pending != null) pending.finish();
        });
    }
}
