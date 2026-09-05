package dev.mich.quotile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** One read at a time. Automatic requests require the user's current opt-in. */
public final class QuotaSync {
    private static final Object LOCK = new Object();
    private static Operation current;
    private QuotaSync() {}
    private static final class Operation {
        final boolean automatic;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        Operation(boolean automatic) { this.automatic=automatic; }
    }
    public static boolean isRunning() { synchronized (LOCK) { return current != null; } }
    public static void refreshAsync(Context context, Runnable callback) { start(context,callback,false); }
    public static void refreshAutomatic(Context context, Runnable callback) { start(context,callback,true); }
    public static void cancelAutomatic() {
        synchronized (LOCK) {
            if (current != null && current.automatic) {
                current.cancelled.set(true);
                AccountClient.cancelRead();
            }
        }
    }
    private static void start(Context context, Runnable callback, boolean automatic) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Operation operation = new Operation(automatic);
        synchronized (LOCK) {
            if (current != null || (automatic && !new QuotaStore(app).automatic())) {
                if (callback != null) main.post(callback);
                return;
            }
            current = operation;
        }
        new Thread(() -> {
            QuotaStore store = new QuotaStore(app);
            long generation = store.generation();
            java.util.function.BooleanSupplier allowed = () -> !operation.cancelled.get()
                    && (!operation.automatic || (store.automatic() && !store.demo()));
            try {
                if (allowed.getAsBoolean() && !store.demo()) {
                    if (!AccountClient.isSignedIn(app)) store.saveError("login_required", generation);
                    else {
                        org.json.JSONObject snapshot = AccountClient.readQuota(app, allowed);
                        if (allowed.getAsBoolean()) store.saveSnapshot(snapshot, generation);
                    }
                }
            } catch (AccountClient.AccountException error) {
                if (allowed.getAsBoolean()) store.saveError(error.getCode(), generation);
            } catch (Exception error) {
                if (allowed.getAsBoolean()) store.saveError("invalid_response", generation);
            } finally {
                synchronized (LOCK) { if (current == operation) current = null; }
                main.post(() -> {
                    WidgetUpdate.updateAll(app);
                    if (callback != null) callback.run();
                });
            }
        }, automatic ? "quotile-opted-in-refresh" : "quotile-user-refresh").start();
    }
}
