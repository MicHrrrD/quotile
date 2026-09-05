package dev.mich.quotile;
import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashSet;
import java.util.Set;
public final class QuotaJobService extends JobService {
    private final Set<JobParameters> running = new HashSet<>();
    @Override public boolean onStartJob(JobParameters params) {
        running.add(params);
        QuotaSync.refreshAsync(this, () -> {
            if (running.remove(params)) jobFinished(params, false);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { running.remove(params); return true; }
}
