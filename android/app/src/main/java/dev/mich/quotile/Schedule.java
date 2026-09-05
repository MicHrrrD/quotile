package dev.mich.quotile;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class Schedule {
    private static final int PERIODIC_ID = 61001, MANUAL_ID = 61002;
    private Schedule() {}
    public static void ensure(Context context) {
        QuotaStore store = new QuotaStore(context);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (store.demo() || store.endpoint().isEmpty() || store.token().isEmpty() || !WidgetUpdate.hasWidgets(context)) {
            scheduler.cancel(PERIODIC_ID); scheduler.cancel(MANUAL_ID); return;
        }
        long interval = store.intervalMinutes() * 60000L;
        JobInfo existing = scheduler.getPendingJob(PERIODIC_ID);
        if (existing != null && existing.getIntervalMillis() == interval) return;
        JobInfo job = new JobInfo.Builder(PERIODIC_ID, new ComponentName(context, QuotaJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(interval, Math.max(5 * 60000L, interval / 3)).build();
        scheduler.schedule(job);
    }
    public static void requestNow(Context context) {
        QuotaStore store = new QuotaStore(context);
        if (store.demo() || store.endpoint().isEmpty() || store.token().isEmpty()) { WidgetUpdate.updateAll(context); return; }
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.schedule(new JobInfo.Builder(MANUAL_ID,
                new ComponentName(context, QuotaJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build());
    }
}
