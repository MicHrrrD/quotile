package dev.mich.quotile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** One read at a time. Automatic requests require the user's current opt-in. */
public final class QuotaSync {
    private static final long WIDGET_BROADCAST_BUDGET_MS = 27000L;
    private static final Object LOCK = new Object();
    private static Operation current;
    private QuotaSync() {}
    private static final class Operation {
        final boolean automatic;
        final long deadline;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean callbackDelivered = new AtomicBoolean(false);
        boolean workerFinished;
        boolean readSucceeded;
        Thread broadcastWatch;
        Operation(boolean automatic, boolean widget) {
            this.automatic = automatic;
            deadline = widget ? SystemClock.elapsedRealtime() + WIDGET_BROADCAST_BUDGET_MS : Long.MAX_VALUE;
        }
    }
    public static boolean isRunning() {
        synchronized (LOCK) { return current != null && !current.cancelled.get(); }
    }
    public static void refreshAsync(Context context, Runnable callback) { start(context,callback,false,false); }
    public static void refreshAutomatic(Context context, Runnable callback) { start(context,callback,true,false); }
    /** Completion can run on a worker thread; it must only finish the broadcast PendingResult. */
    public static void refreshFromWidget(Context context, Runnable finishBroadcast) {
        start(context,finishBroadcast,false,true);
    }
    public static void cancelAutomatic() {
        synchronized (LOCK) {
            if (current != null && current.automatic) {
                current.cancelled.set(true);
                AccountClient.cancelRead();
            }
        }
    }
    private static void start(Context context, Runnable callback, boolean automatic, boolean widget) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Operation operation = new Operation(automatic, widget);
        QuotaStore store = new QuotaStore(app);
        long generation = store.generation();
        synchronized (LOCK) {
            if (current != null || (automatic && !store.automatic())) {
                if (callback != null) main.post(callback);
                return;
            }
            current = operation;
        }
        Runnable deliverCallback = () -> {
            if (!operation.callbackDelivered.compareAndSet(false, true)) return;
            if (operation.broadcastWatch != null && Thread.currentThread() != operation.broadcastWatch)
                operation.broadcastWatch.interrupt();
            if (callback != null) callback.run();
        };
        if (widget) {
            operation.broadcastWatch = new Thread(() -> {
                try {
                    long remaining;
                    while ((remaining = operation.deadline - SystemClock.elapsedRealtime()) > 0)
                        Thread.sleep(remaining);
                } catch (InterruptedException completed) { return; }
                // Finish independently of the main looper and Android's DNS/transport calls.
                // Those can resist interruption. Keep their occupied slot until the worker
                // exits, rejecting more taps rather than accumulating queued network reads.
                operation.cancelled.set(true);
                deliverCallback.run();
                synchronized (LOCK) {
                    if (current == operation && !operation.workerFinished) {
                        store.saveError("network_timeout", generation);
                    }
                }
                main.post(() -> updateWidgets(app));
            }, "quotile-widget-broadcast-deadline");
            operation.broadcastWatch.setDaemon(true);
            operation.broadcastWatch.start();
        }
        // RemoteViews show a busy state immediately, without launching an activity.
        main.post(() -> updateWidgets(app));
        new Thread(() -> {
            java.util.function.BooleanSupplier allowed = () -> !operation.cancelled.get()
                    && SystemClock.elapsedRealtime() < operation.deadline
                    && (!operation.automatic || (store.automatic() && !store.demo()));
            boolean resultSaved = false;
            try {
                if (allowed.getAsBoolean() && !store.demo()) {
                    if (!AccountClient.isSignedIn(app)) {
                        synchronized (LOCK) {
                            if (allowed.getAsBoolean()) {
                                store.saveError("login_required", generation);
                                resultSaved = true;
                            }
                        }
                    }
                    else {
                        org.json.JSONObject snapshot = AccountClient.readQuota(app, allowed);
                        synchronized (LOCK) {
                            if (allowed.getAsBoolean()) {
                                store.saveSnapshot(snapshot, generation);
                                resultSaved = true;
                                operation.readSucceeded = store.generation() == generation;
                            }
                        }
                    }
                }
            } catch (AccountClient.AccountException error) {
                synchronized (LOCK) {
                    if (allowed.getAsBoolean()) {
                        store.saveError(error.getCode(), generation);
                        resultSaved = true;
                    }
                }
            } catch (Exception error) {
                synchronized (LOCK) {
                    if (allowed.getAsBoolean()) {
                        store.saveError("invalid_response", generation);
                        resultSaved = true;
                    }
                }
            } finally {
                synchronized (LOCK) {
                    if (!resultSaved && SystemClock.elapsedRealtime() >= operation.deadline)
                        store.saveError("network_timeout", generation);
                    operation.workerFinished = true;
                    if (current == operation) current = null;
                }
                main.post(() -> {
                    // Leave enough of the existing broadcast budget for the short reveal.
                    // Failures, cancelled reads and replaced accounts always settle directly.
                    if (operation.readSucceeded && !operation.cancelled.get()
                            && store.generation() == generation && !isRunning()
                            && SystemClock.elapsedRealtime() < operation.deadline - 1000L) {
                        try { WidgetMotion.reveal(app, generation, deliverCallback); }
                        catch (RuntimeException unavailableHost) {
                            try { updateWidgets(app); }
                            finally { deliverCallback.run(); }
                        }
                    } else {
                        try { updateWidgets(app); }
                        finally { deliverCallback.run(); }
                    }
                });
            }
        }, automatic ? "quotile-opted-in-refresh" : "quotile-user-refresh").start();
    }
    private static void updateWidgets(Context context) {
        try { WidgetUpdate.updateAll(context); }
        catch (RuntimeException unavailableHost) {
            // The snapshot remains saved if a widget host disappears during its update.
        }
    }
}
