package dev.mich.quotile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** The launcher renders every animation frame; the app only starts and settles the reveal. */
final class WidgetMotion {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Native view animations last 900ms. The target bar is already prepared underneath.
    private static final long SETTLE_MS = 980L;
    private static volatile Reveal current;

    private WidgetMotion() {}
    static boolean isRunning() { return current != null; }

    /** Called on the main thread; keeps the existing broadcast/job alive for final cleanup. */
    static void reveal(Context context, long generation, Runnable completion) {
        cancel();
        Context app = context.getApplicationContext();
        QuotaStore store = new QuotaStore(app);
        WidgetState state = store.state();
        PowerManager power = app.getSystemService(PowerManager.class);
        if (!ValueAnimator.areAnimatorsEnabled() || !WidgetUpdate.hasWidgets(app)
                || (power != null && !power.isInteractive()) || generation != store.generation()
                || QuotaSync.isRunning() || !state.configured
                || (state.error != null && !state.error.isEmpty())) {
            WidgetUpdate.updateAll(app);
            completion.run();
            return;
        }
        Reveal reveal = new Reveal(app, completion);
        current = reveal;
        try {
            // One complete update installs finite animations inside the launcher.
            // No Handler frame loop, bitmap transport, or per-frame Binder calls.
            WidgetUpdate.beginReveal(app, state);
            MAIN.postDelayed(reveal, SETTLE_MS);
        } catch (RuntimeException unavailableHost) {
            reveal.finish();
        }
    }

    /** Resize, theme/account changes and a newer refresh restore authoritative static data. */
    static void cancel() {
        Reveal reveal = current;
        if (reveal == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) reveal.finish();
        else MAIN.post(reveal::finish);
    }

    private static final class Reveal implements Runnable {
        final Context app;
        final Runnable completion;
        Reveal(Context app, Runnable completion) {
            this.app = app;
            this.completion = completion;
        }
        @Override public void run() { finish(); }
        void finish() {
            if (current != this) return;
            current = null;
            MAIN.removeCallbacks(this);
            try { WidgetUpdate.updateAll(app); }
            catch (RuntimeException unavailableHost) { /* The saved snapshot is authoritative. */ }
            finally { completion.run(); }
        }
    }
}
