package dev.mich.quotile;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            DeviceLoginTests.run(app);
            require(!store.automatic(), "Automatic refresh must default OFF");
            long manualGeneration = store.generation();
            store.setAutomatic(true, 15);
            Schedule.apply(app);
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(), "Signed-out auto mode must not schedule");
            store.setAutomatic(false, 30);
            require(store.generation()==manualGeneration,"Auto switch must not discard a manual refresh result");
            store.savePreferences("dark",false,false,60);
            require(store.generation()==manualGeneration,"Theme and interval must not invalidate a manual read");
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
            Activity finished = activity;
            runOnMainSync(finished::finish);
            activity = null;
            waitForIdleSync();
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            ActivityMonitor refreshMonitor = addMonitor(RefreshActivity.class.getName(), null, false);
            CountDownLatch handled = new CountDownLatch(1);
            app.sendOrderedBroadcast(new Intent(app, WidgetRefreshReceiver.class)
                    .setAction(WidgetRefreshReceiver.ACTION_REFRESH), null,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) { handled.countDown(); }
                    }, new Handler(Looper.getMainLooper()), 0, null, null);
            require(handled.await(5,TimeUnit.SECONDS),"Widget refresh broadcast must finish while signed out");
            waitForIdleSync();
            require(monitor.getHits()==0 && refreshMonitor.getHits()==0,
                    "Widget refresh must not launch an Activity");
            removeMonitor(monitor);
            removeMonitor(refreshMonitor);
            require(!QuotaSync.isRunning(),"Signed-out widget refresh must not leave a worker");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),
                    "Manual widget refresh must not create scheduled jobs");
            File previews = new File(app.getFilesDir(),"widget-previews");
            require(previews.isDirectory() || previews.mkdirs(),"Preview directory");
            WidgetFooterTests.run(this, app, previews);
            WidgetMotionTests.run(this, app, previews);
            int[][] sizes = {{350,64},{350,150},{700,150},{160,150},{110,40},{110,64}};
            for (boolean dark : new boolean[]{false,true}) {
                for (int[] size : sizes) {
                    WidgetState demo = QuotaStore.demoState();
                    Bitmap bitmap = nativeRender(app,size[0],size[1],demo,dark);
                    require(bitmap.getWidth()>0 && bitmap.getHeight()>0,"Widget must render");
                    savePreview(bitmap,previews,(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-dual.png");
                    bitmap.recycle();
                    demo.fiveHourRemaining = null;
                    demo.fiveHourResetAt = 0;
                    bitmap = nativeRender(app,size[0],size[1],demo,dark);
                    require(bitmap.getWidth()>0 && bitmap.getHeight()>0,"Weekly-only widget must render");
                    savePreview(bitmap,previews,(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-weekly.png");
                    bitmap.recycle();
                }
            }
            store.savePreferences("dark", false);
            activity = startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(!QuotaSync.isRunning(),"Reopening/theme change must not refresh");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),"Theme/launcher must not schedule");
            result.putString("stream", "PASS: settings launch, reopen, launcher callbacks and theme changes remain offline with auto OFF; opt-in defaults and task cancellation; widget broadcast does not launch an Activity; 24 base native widget render cases plus mixed-script footer, reset-credit, font-scale and Codex source layout cases; true AppWidgetHost receives sized reveal frames with rounded ends; hardware spinner changes over elapsed time and stops when hidden; reduced motion and reveal completion/cancellation remain offline; OAuth callback, PKCE, quota/reset parser, encrypted vault and synthetic device-login contracts passed.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName()+": "+error.getMessage()+"\n");
        } finally {
            if(activity!=null) { Activity last=activity; runOnMainSync(last::finish); }
        }
        finish(resultCode,result);
    }
    private static void savePreview(Bitmap bitmap,File directory,String name) throws Exception {
        try(FileOutputStream output=new FileOutputStream(new File(directory,name))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Write native render preview");
        }
    }
    private Bitmap nativeRender(Context app,int width,int height,WidgetState state,boolean dark) {
        Bitmap[] result = new Bitmap[1];
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                View view=WidgetRenderer.remoteViews(app,width,height,state,dark,false).apply(app,null);
                require(view.findViewById(R.id.widget_value) instanceof TextView,
                        "Delivered widget text must be a native TextView");
                require(view.findViewById(R.id.widget_progress) instanceof ProgressBar,
                        "Delivered widget bar must be a native ProgressBar");
                result[0]=WidgetRenderer.render(app,width,height,state,dark);
                int expected=Math.round(width*app.getResources().getDisplayMetrics().density);
                require(Math.abs(result[0].getWidth()-expected)<=1,"Preview must use device pixel density");
                if(width==350 && height==64 && !dark) {
                    // The numeric top-right region must contain ink. This caught native
                    // singleLine scrolling placing right-aligned text outside its box.
                    float density=app.getResources().getDisplayMetrics().density;
                    int ink=0;
                    for(int y=Math.round(2*density);y<Math.round(32*density);y++)
                        for(int x=Math.round(175*density);x<Math.round(295*density);x++) {
                            int color=result[0].getPixel(x,y);
                            if(android.graphics.Color.alpha(color)>200
                                    && android.graphics.Color.red(color)<70
                                    && android.graphics.Color.green(color)<70
                                    && android.graphics.Color.blue(color)<70) ink++;
                        }
                    require(ink>=15,"Compact right-aligned percentage must be visibly rendered");
                }
            } catch(Throwable error) { failure[0]=error; }
        });
        if(failure[0]!=null) throw new AssertionError("Native widget inflation/render failed",failure[0]);
        return result[0];
    }
}
