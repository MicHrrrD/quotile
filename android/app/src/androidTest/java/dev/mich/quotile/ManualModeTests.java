package dev.mich.quotile;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

/** On-device checks with no real credentials and no network fixtures. */
public final class ManualModeTests extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        int resultCode = Activity.RESULT_CANCELED;
        try {
            Context app = getTargetContext();
            QuotaStore store = new QuotaStore(app);
            store.migrateManualMode();
            AccountContractTests.run(app);
            require(!store.automatic(), "Automatic refresh must default OFF");
            store.setAutomatic(true, 15);
            Schedule.apply(app);
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(), "Signed-out auto mode must not schedule");
            store.setAutomatic(false, 30);
            android.app.job.JobInfo oldTask = new android.app.job.JobInfo.Builder(Schedule.OPTIONAL_ID,
                    new android.content.ComponentName(app,QuotaJobService.class)).setMinimumLatency(86400000).build();
            app.getSystemService(JobScheduler.class).schedule(oldTask);
            Schedule.apply(app);
            require(app.getSystemService(JobScheduler.class).getPendingJob(Schedule.OPTIONAL_ID)==null,"Switching OFF cancels pending task");
            require(!AccountClient.isSignedIn(app), "Fresh test installation must be signed out");
            store.savePreferences("light", false);
            activity = startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(!QuotaSync.isRunning(), "Opening settings must not start a refresh");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(), "No scheduled jobs");
            new SlimWidgetProvider().onUpdate(app, AppWidgetManager.getInstance(app), new int[0]);
            new DetailWidgetProvider().onUpdate(app, AppWidgetManager.getInstance(app), new int[0]);
            require(!QuotaSync.isRunning(), "Launcher updates must render cached data only");
            require(store.state().updatedAt == 0, "Opening/adding widgets must not invent a read timestamp");
            int[][] sizes = {{350,64},{350,150},{700,150},{160,150},{110,40},{110,64}};
            for (boolean dark : new boolean[]{false,true}) {
                for (int[] size : sizes) {
                    WidgetState demo = QuotaStore.demoState();
                    Bitmap bitmap = WidgetRenderer.render(app,size[0],size[1],demo,dark);
                    require(bitmap.getWidth()>0 && bitmap.getHeight()>0,"Widget must render");
                    bitmap.recycle();
                    demo.fiveHourRemaining = null;
                    demo.fiveHourResetAt = 0;
                    bitmap = WidgetRenderer.render(app,size[0],size[1],demo,dark);
                    require(bitmap.getWidth()>0 && bitmap.getHeight()>0,"Weekly-only widget must render");
                    bitmap.recycle();
                }
            }
            store.savePreferences("dark", false);
            Activity old = activity;
            runOnMainSync(old::finish);
            activity = startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(!QuotaSync.isRunning(),"Reopening/theme change must not refresh");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),"Theme/launcher must not schedule");
            result.putString("stream", "PASS: settings launch, reopen, launcher callbacks and theme changes remain offline with auto OFF; opt-in defaults and task cancellation; 24 Android Canvas widget render cases; OAuth callback, PKCE, quota parser and encrypted vault checks passed.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName()+": "+error.getMessage()+"\n");
        } finally {
            if(activity!=null) { Activity last=activity; runOnMainSync(last::finish); }
        }
        finish(resultCode,result);
    }
}
