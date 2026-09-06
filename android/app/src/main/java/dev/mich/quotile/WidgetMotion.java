package dev.mich.quotile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.RemoteViews;

/** A short, local reveal after a successful read. Never schedules a read or a service. */
final class WidgetMotion {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DURATION_MS = 720L;
    private static final long FRAME_MS = 32L;
    private static volatile Reveal current;

    private WidgetMotion() {}

    static boolean isRunning() { return current != null; }
    static float fraction() { Reveal reveal = current; return reveal == null ? 1f : reveal.fraction; }

    /** Called on the main thread, with the broadcast/job kept alive until completion. */
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
        Reveal reveal = new Reveal(app, generation, state, completion);
        current = reveal;
        reveal.run();
    }

    /** A resize, theme change, logout, or newer refresh settles any prior reveal. */
    static void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(WidgetMotion::cancel);
            return;
        }
        Reveal reveal = current;
        if (reveal != null) reveal.finish();
    }

    static float ease(float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        float remaining = 1f - t;
        return 1f - remaining * remaining * remaining;
    }

    /** Only fill levels move, never the text or rounded bounds. */
    static RemoteViews progressFrame(Context context, WidgetState state, float fraction) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        applyProgress(views, state, fraction);
        return views;
    }

    static void applyProgress(RemoteViews views, WidgetState state, float fraction) {
        views.setInt(R.id.widget_progress, "setProgress", progress(state.weeklyRemaining, fraction));
        views.setInt(R.id.widget_secondary_progress, "setProgress", progress(state.fiveHourRemaining, fraction));
    }

    private static int progress(Double percent, float fraction) {
        if (percent == null || !Double.isFinite(percent)) return 0;
        float bounded = Float.isFinite(fraction) ? Math.max(0f, Math.min(1f, fraction)) : 1f;
        return (int) Math.round(Math.max(0d, Math.min(100d, percent)) * 100d * bounded);
    }

    private static final class Reveal implements Runnable {
        final Context app;
        final long generation, startedAt;
        final WidgetState state;
        final Runnable completion;
        final java.util.ArrayList<WidgetUpdate.Prepared> frames;
        float fraction;
        boolean firstFrame = true;

        Reveal(Context app, long generation, WidgetState state, Runnable completion) {
            this.app = app;
            this.generation = generation;
            this.state = state;
            this.completion = completion;
            frames = WidgetUpdate.prepareMotion(app, state);
            startedAt = SystemClock.elapsedRealtime();
        }

        @Override public void run() {
            if (current != this) return;
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            PowerManager power = app.getSystemService(PowerManager.class);
            if (elapsed >= DURATION_MS || new QuotaStore(app).generation() != generation
                    || QuotaSync.isRunning() || !ValueAnimator.areAnimatorsEnabled()
                    || (power != null && !power.isInteractive())) {
                finish();
                return;
            }
            fraction = firstFrame ? 0f : ease(elapsed / (float) DURATION_MS);
            firstFrame = false;
            try {
                // Partial updates modify the wrapper rather than its selected child on
                // Android hosts. Full native frames preserve every exact Fold size.
                for (WidgetUpdate.Prepared frame : frames) frame.update(fraction);
                MAIN.postDelayed(this, FRAME_MS);
            } catch (RuntimeException unavailableHost) {
                finish();
            }
        }

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
