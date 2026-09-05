package dev.mich.quotile;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** Optional periodic reads. An explicit stored opt-in is always required. */
public final class Schedule {
    static final int OPTIONAL_ID = 62001;
    private Schedule() {}
    public static void apply(Context context) {
        Context app = context.getApplicationContext();
        QuotaStore store = new QuotaStore(app);
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!store.automatic() || store.demo() || !AccountClient.isSignedIn(app)) {
            jobs.cancel(OPTIONAL_ID);
            QuotaSync.cancelAutomatic();
            return;
        }
        long interval = store.intervalMinutes() * 60000L;
        JobInfo existing = jobs.getPendingJob(OPTIONAL_ID);
        if (existing != null && existing.isPeriodic() && existing.getIntervalMillis() == interval) return;
        jobs.schedule(new JobInfo.Builder(OPTIONAL_ID, new ComponentName(app, QuotaJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(interval, 5 * 60000L).build());
    }
}
