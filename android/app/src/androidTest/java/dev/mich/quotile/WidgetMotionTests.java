package dev.mich.quotile;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real host delivery and native pixel checks; all quota values are synthetic. */
final class WidgetMotionTests {
    private static final int[] TEXT_IDS = { R.id.widget_label, R.id.widget_value,
            R.id.widget_reset, R.id.widget_reset_count, R.id.widget_reset_expiry,
            R.id.widget_secondary_label, R.id.widget_secondary_value,
            R.id.widget_secondary_reset, R.id.widget_status };

    static void run(Instrumentation instrumentation, Context app, File previews) throws Exception {
        require(ValueAnimator.areAnimatorsEnabled(), "Motion verification requires emulator animations enabled");
        for (boolean dark : new boolean[]{false, true}) {
            assertFrames(instrumentation, app, previews, 350, 64, false, dark, 75d, null);
            assertFrames(instrumentation, app, previews, 250, 235, false, dark, 75d, null);
            assertFrames(instrumentation, app, previews, 350, 180, true, dark, 75d, 42d);
        }
        for (Double amount : new Double[]{0d, 100d, null})
            assertFrames(instrumentation, app, previews, 350, 180, true, false, amount, amount);
        assertAttachedSpinner(instrumentation, app, previews);
        assertHostDelivery(instrumentation, app, previews);
    }

    private static void assertFrames(Instrumentation instrumentation, Context app, File previews,
            int width, int height, boolean dual, boolean dark, Double weekly, Double secondary) {
        onMain(instrumentation, () -> {
            WidgetState state = state(weekly, dual ? secondary : null);
            ViewGroup card = (ViewGroup) WidgetRenderer.remoteViews(app, width, height,
                    state, dark, false).apply(app, new FrameLayout(app));
            layout(card, width, height);
            String[] labels = new String[TEXT_IDS.length];
            Rect[] positions = new Rect[TEXT_IDS.length];
            for (int i = 0; i < TEXT_IDS.length; i++) {
                TextView label = card.findViewById(TEXT_IDS[i]);
                labels[i] = label.getText().toString();
                positions[i] = bounds(label);
            }
            ProgressBar primary = card.findViewById(R.id.widget_progress);
            ProgressBar second = card.findViewById(R.id.widget_secondary_progress);
            Rect primaryBounds = bounds(primary), secondBounds = bounds(second);
            int secondVisibility = second.getVisibility();
            float[] fractions = {0f, .2f, .5f, 1f};
            for (int frame = 0; frame < fractions.length; frame++) {
                float fraction = fractions[frame];
                WidgetMotion.progressFrame(app, state, fraction).reapply(app, card);
                layout(card, width, height);
                require(primary.getProgress() == expected(weekly, fraction), "Native primary progress frame");
                require(second.getProgress() == expected(dual ? secondary : null, fraction),
                        "Native secondary progress frame");
                require(bounds(primary).equals(primaryBounds) && bounds(second).equals(secondBounds),
                        "Reveal changes fill only, preserving original bar position and height");
                require(second.getVisibility() == secondVisibility, "Reveal must not make hidden quota rows visible");
                for (int i = 0; i < TEXT_IDS.length; i++) {
                    TextView label = card.findViewById(TEXT_IDS[i]);
                    require(labels[i].equals(label.getText().toString()) && positions[i].equals(bounds(label)),
                            "Reveal must preserve exact text and balanced content spacing");
                }
                if (weekly != null && weekly == 75d) {
                    if (fraction > 0f) assertCapsulePixels(primary, dark);
                    if (dual && fraction > 0f) assertCapsulePixels(second, dark);
                    if (!dark || width == 250)
                        save(draw(card), previews, "motion-" + width + "x" + height
                                + (dual ? "-dual" : "-weekly") + (dark ? "-dark" : "-light")
                                + "-frame-" + frame + ".png");
                }
            }
        });
    }

    private static void assertCapsulePixels(ProgressBar bar, boolean dark) {
        Bitmap bitmap = draw(bar);
        int edge = -1, mid = bitmap.getHeight() / 2;
        for (int x = 0; x < bitmap.getWidth(); x++)
            if (isFill(bitmap.getPixel(x, mid), dark)) edge = x;
        String sample = " [" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + ", edge=" + edge + ", progress=" + bar.getProgress() + ", dark=" + dark + "]";
        // Early secondary frames can be approximately one bar-height wide: a
        // round dot is the correct first capsule, not a missing fill.
        require(edge >= 2, "A revealed native bar must have a visible fill" + sample);
        int inset = Math.max(1, Math.min(bitmap.getHeight(), edge + 1) / 8);
        int top = Math.max(0, bitmap.getHeight() / 10);
        require(isFill(bitmap.getPixel(edge - inset, mid), dark), "Capsule end must reach its center line" + sample);
        require(!isFill(bitmap.getPixel(edge - inset, top), dark),
                "Moving fill retains its rounded right end, without a vertical clipped edge" + sample);
        require(!isFill(bitmap.getPixel(inset, top), dark), "Moving fill retains its rounded left end" + sample);
        bitmap.recycle();
    }

    private static boolean isFill(int color, boolean dark) {
        return Color.alpha(color) > 200 && (dark ? Color.red(color) > 200 : Color.red(color) < 80);
    }

    private static void assertAttachedSpinner(Instrumentation instrumentation, Context app, File previews)
            throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ViewGroup[] card = new ViewGroup[1];
        try {
            for (boolean dark : new boolean[]{false, true}) {
                onMain(instrumentation, () -> {
                    card[0] = (ViewGroup) WidgetRenderer.remoteViews(app, 350, 180,
                            state(75d, 42d), dark, true, true).apply(app, new FrameLayout(app));
                    FrameLayout root = new FrameLayout(activity);
                    root.addView(card[0], new FrameLayout.LayoutParams(dp(app, 350), dp(app, 180)));
                    activity.setContentView(root);
                });
                SystemClock.sleep(160);
                Bitmap first = screenshot(instrumentation, card[0]);
                SystemClock.sleep(190);
                Bitmap second = screenshot(instrumentation, card[0]);
                onMain(instrumentation, () -> {
                    ProgressBar spinner = card[0].findViewById(dark
                            ? R.id.widget_refresh_spinner_dark : R.id.widget_refresh_spinner);
                    ImageButton refresh = card[0].findViewById(R.id.widget_refresh);
                    require(spinner.isShown() && spinner.getIndeterminateDrawable() instanceof Animatable,
                            "Busy widget hosts a native animated refresh arrow");
                    require(((Animatable) spinner.getIndeterminateDrawable()).isRunning(),
                            "Attached busy spinner must be running");
                    require(!spinner.isClickable() && !spinner.isFocusable()
                                    && spinner.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            "Animated overlay does not intercept taps or create a duplicate accessibility target");
                    require(!refresh.isEnabled() && refresh.getContentDescription().toString().contains("正在刷新"),
                            "Original refresh target retains its busy semantics");
                    Rect area = bounds(spinner);
                    int different = 0;
                    for (int y = area.top; y < area.bottom; y++)
                        for (int x = area.left; x < area.right; x++)
                            if (first.getPixel(x, y) != second.getPixel(x, y)) different++;
                    require(different > 20, "Actual hardware-rendered arrow pixels must rotate over elapsed time");
                    WidgetRenderer.remoteViews(app, 350, 180, state(75d, 42d), dark, false, true)
                            .reapply(app, card[0]);
                    require(spinner.getVisibility() == View.GONE,
                            "Finishing refresh hides the launcher animation");
                    require(refresh.isEnabled(), "Finished refresh restores the original button");
                    WidgetRenderer.remoteViews(app, 350, 180, state(75d, 42d), dark, true, false)
                            .reapply(app, card[0]);
                    require(card[0].findViewById(R.id.widget_refresh_spinner).getVisibility() == View.GONE
                                    && card[0].findViewById(R.id.widget_refresh_spinner_dark).getVisibility() == View.GONE,
                            "Reduced motion keeps the static wait glyph and starts no spinner");
                });
                SystemClock.sleep(80);
                onMain(instrumentation, () -> {
                    ProgressBar spinner = card[0].findViewById(dark
                            ? R.id.widget_refresh_spinner_dark : R.id.widget_refresh_spinner);
                    require(!((Animatable) spinner.getIndeterminateDrawable()).isRunning(),
                            "Hidden refresh arrow must stop after visibility propagation");
                });
                save(first, previews, "motion-spinner-" + (dark ? "dark" : "light") + "-0.png");
                save(second, previews, "motion-spinner-" + (dark ? "dark" : "light") + "-1.png");
            }
        } finally { onMain(instrumentation, activity::finish); }
    }

    private static void assertHostDelivery(Instrumentation instrumentation, Context app, File previews)
            throws Exception {
        QuotaStore store = new QuotaStore(app);
        store.savePreferences("light", true, false, 30);
        Activity activity = instrumentation.startActivitySync(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        RecordingHost[] host = new RecordingHost[1];
        RecordingView[] card = new RecordingView[1];
        try {
            onMain(instrumentation, () -> {
                host[0] = new RecordingHost(app);
                int id = host[0].allocateAppWidgetId();
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                Bundle options = new Bundle();
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 350);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 350);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
                options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        new ArrayList<>(Arrays.asList(new SizeF(350, 180), new SizeF(250, 235))));
                require(manager.bindAppWidgetIdIfAllowed(id,
                                new ComponentName(app, DetailWidgetProvider.class), options),
                        "Emulator must grant the test host permission to bind its own widget");
                card[0] = (RecordingView) host[0].createView(activity, id, manager.getAppWidgetInfo(id));
                card[0].setPadding(0, 0, 0, 0);
                FrameLayout root = new FrameLayout(activity);
                root.addView(card[0], new FrameLayout.LayoutParams(dp(app, 350), dp(app, 180)));
                activity.setContentView(root);
                host[0].startListening();
                WidgetUpdate.updateAll(app);
            });
            SystemClock.sleep(250);
            instrumentation.waitForIdleSync();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            onMain(instrumentation, () -> {
                card[0].recording = true;
                card[0].frames.clear();
                WidgetMotion.reveal(app, store.generation(), () -> {
                    callbacks.incrementAndGet(); completed.countDown();
                });
                require(WidgetMotion.isRunning(), "Successful refresh starts a finite reveal for bound widgets");
            });
            SystemClock.sleep(200);
            Bitmap mid = screenshot(instrumentation, card[0]);
            require(completed.await(3, TimeUnit.SECONDS), "Reveal must finish promptly and release its callback");
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                boolean zero = false, intermediate = false;
                int previous = -1;
                for (int[] frame : card[0].frames) {
                    if (frame[0] == 0) { zero = true; previous = 0; }
                    if (!zero) continue;
                    require(frame[0] >= previous, "Host receives monotonically increasing pill fill");
                    require(frame[0] <= 6800 && frame[1] <= 8400, "Reveal must never overshoot actual quotas");
                    if (frame[0] > 0 && frame[0] < 6800 && frame[1] > 0 && frame[1] < 8400)
                        intermediate = true;
                    previous = frame[0];
                }
                require(zero && intermediate && previous == 6800,
                        "Actual AppWidgetService must deliver every reveal phase through sized RemoteViews");
                ProgressBar second = card[0].findViewById(R.id.widget_secondary_progress);
                require(second != null && second.getProgress() == 8400, "Final dual quota is accurate in the host");
                require(!WidgetMotion.isRunning() && WidgetMotion.fraction() == 1f && callbacks.get() == 1,
                        "Finite reveal ends with full data and a single completion callback");
            });
            save(mid, previews, "motion-host-elapsed-middle.png");
            save(screenshot(instrumentation, card[0]), previews, "motion-host-completed.png");

            AtomicInteger cancelledCallbacks = new AtomicInteger();
            onMain(instrumentation, () -> {
                WidgetMotion.reveal(app, store.generation(), cancelledCallbacks::incrementAndGet);
                require(WidgetMotion.isRunning(), "Second reveal starts");
            });
            SystemClock.sleep(100);
            onMain(instrumentation, WidgetMotion::cancel);
            SystemClock.sleep(850);
            onMain(instrumentation, () -> {
                require(!WidgetMotion.isRunning() && WidgetMotion.fraction() == 1f
                                && cancelledCallbacks.get() == 1,
                        "Cancellation settles once and leaves no reveal loop running");
                require(((ProgressBar) card[0].findViewById(R.id.widget_progress)).getProgress() == 6800,
                        "Cancelling an animation restores true saved usage");
            });
            require(!QuotaSync.isRunning(), "Synthetic reveal must not start a network read");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),
                    "Transient widget motion must not schedule background work");
        } finally {
            onMain(instrumentation, () -> {
                WidgetMotion.cancel();
                if (host[0] != null) { host[0].stopListening(); host[0].deleteHost(); }
                activity.finish();
            });
            store.savePreferences("light", false, false, 30);
        }
    }

    private static final class RecordingHost extends AppWidgetHost {
        RecordingHost(Context context) { super(context, 61037); }
        @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
            return new RecordingView(context);
        }
    }
    private static final class RecordingView extends AppWidgetHostView {
        boolean recording;
        final List<int[]> frames = new ArrayList<>();
        RecordingView(Context context) { super(context); }
        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            ProgressBar first = findViewById(R.id.widget_progress);
            ProgressBar second = findViewById(R.id.widget_secondary_progress);
            if (recording && first != null && second != null)
                frames.add(new int[]{first.getProgress(), second.getProgress()});
        }
    }

    private static WidgetState state(Double weekly, Double secondary) {
        WidgetState state = QuotaStore.demoState();
        state.demo = false;
        state.weeklyRemaining = weekly;
        state.fiveHourRemaining = secondary;
        return state;
    }
    private static int expected(Double amount, float fraction) {
        return amount == null ? 0 : Math.round((float) (amount * 100) * fraction);
    }
    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
    private static Rect bounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
    private static void layout(View view, int width, int height) {
        int w = dp(view.getContext(), width), h = dp(view.getContext(), height);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
    private static Bitmap screenshot(Instrumentation instrumentation, View view) {
        int[] position = new int[4];
        onMain(instrumentation, () -> {
            view.getLocationOnScreen(position);
            position[2] = view.getWidth(); position[3] = view.getHeight();
        });
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        require(screen != null, "Capture actual Android animation frames");
        require(position[0] >= 0 && position[1] >= 0 && position[2] > 0 && position[3] > 0
                        && position[0] + position[2] <= screen.getWidth()
                        && position[1] + position[3] <= screen.getHeight(),
                "Emulator viewport must contain the native card [card=" + Arrays.toString(position)
                        + ", screen=" + screen.getWidth() + "x" + screen.getHeight() + "]");
        Bitmap card = Bitmap.createBitmap(screen, position[0], position[1], position[2], position[3]);
        if (card != screen) screen.recycle();
        return card;
    }
    private static void save(Bitmap bitmap, File directory, String name) {
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Save native animation frame");
        } catch (Exception error) { throw new AssertionError("Save motion preview", error); }
        finally { bitmap.recycle(); }
    }
    private static void onMain(Instrumentation instrumentation, Runnable action) {
        Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failed) { error[0] = failed; }
        });
        if (error[0] != null) throw new AssertionError("Widget motion: " + error[0].getMessage(), error[0]);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
