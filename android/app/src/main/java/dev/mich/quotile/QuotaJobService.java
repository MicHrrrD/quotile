package dev.mich.quotile;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashSet;
import java.util.Set;

public final class QuotaJobService extends JobService {
    private final Set<JobParameters> running = new HashSet<>();
    @Override public boolean onStartJob(JobParameters parameters) {
        QuotaStore store = new QuotaStore(this);
        if (!store.automatic() || store.demo() || !AccountClient.isSignedIn(this)) return false;
        running.add(parameters);
        QuotaSync.refreshAutomatic(this, () -> {
            if (running.remove(parameters)) jobFinished(parameters, false);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        running.remove(parameters);
        QuotaSync.cancelAutomatic();
        return false; // No immediate retry; only the next opted-in interval or a user tap.
    }
}
