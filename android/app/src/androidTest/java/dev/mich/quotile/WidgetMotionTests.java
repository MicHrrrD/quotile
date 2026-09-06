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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.View;
import android.view.Choreographer;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewFlipper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real host delivery and native pixel checks; all quota values are synthetic. */
final class WidgetMotionTests {
    private static final long REVEAL_DURATION_MS = 900;
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
        for (Double amount : new Double[]{0d, .1d, 100d, null})
            assertFrames(instrumentation, app, previews, 350, 180, true, false, amount, amount);
        assertWorkerInflationHandoff(instrumentation, app, previews);
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
            Bitmap original = draw(card);
            WidgetRenderer.remoteViews(app, width, height, state, dark, false, true, true)
                    .reapply(app, card);
            layout(card, width, height);
            require(bounds(primary).equals(primaryBounds) && bounds(second).equals(secondBounds),
                    "Reveal preserves the exact original bar position and height");
            require(second.getVisibility() == secondVisibility, "Reveal does not expose a hidden quota row");
            for (int i = 0; i < TEXT_IDS.length; i++) {
                TextView label = card.findViewById(TEXT_IDS[i]);
                require(labels[i].equals(label.getText().toString()) && positions[i].equals(bounds(label)),
                        "Reveal preserves all text and the balanced content spacing");
            }
            assertNativeReveal(card, primary, weekly, false);
            assertNativeReveal(card, second, dual ? secondary : null, true);
            // Settling uses the original native bar, with bit-exact final pixels.
            WidgetRenderer.remoteViews(app, width, height, state, dark, false, true, false)
                    .reapply(app, card);
            layout(card, width, height);
            require(primary.getProgress() == expected(weekly) && second.getProgress() == expected(dual ? secondary : null),
                    "Settled bars retain the exact saved percentages");
            require(card.findViewById(R.id.widget_reveal).getVisibility() == View.GONE
                            && card.findViewById(R.id.widget_secondary_reveal).getVisibility() == View.GONE,
                    "Settling removes both native reveal layers");
            Bitmap settled = draw(card);
            require(original.sameAs(settled), "Native reveal settles into the original pixel-identical widget");
            original.recycle(); settled.recycle();
            WidgetRenderer.remoteViews(app, width, height, state, dark, false, false, true)
                    .reapply(app, card);
            require(card.findViewById(R.id.widget_reveal).getVisibility() == View.GONE
                            && card.findViewById(R.id.widget_secondary_reveal).getVisibility() == View.GONE
                            && primary.getProgress() == expected(weekly),
                    "Reduced motion keeps the accurate static bars without a reveal overlay");
            if (weekly != null && weekly == 75d) {
                assertCapsulePixels(primary, dark);
                if (dual) assertCapsulePixels(second, dark);
            }

        });
    }

    private static void assertNativeReveal(ViewGroup card, ProgressBar bar, Double amount, boolean secondary) {
        ViewGroup overlay = card.findViewById(secondary ? R.id.widget_secondary_reveal : R.id.widget_reveal);
        int target = (int) Math.ceil(bar.getWidth() * expected(amount) / 10000d);
        boolean eligible = bar.getVisibility() == View.VISIBLE && target > bar.getHeight();
        require((overlay.getVisibility() == View.VISIBLE) == eligible,
                "Zero, unknown and tiny quotas use the unchanged native static capsule");
        if (!eligible) return;
        require(bar.getProgress() == expected(amount)
                        && bar.getProgressTintList() != null
                        && Color.alpha(bar.getProgressTintList().getDefaultColor()) == 0,
                "Native reveal preloads the true quota level behind a transparent fill");
        require(overlay.getLeft() == bar.getLeft() && overlay.getTop() == bar.getTop()
                        && overlay.getWidth() == target && overlay.getHeight() == bar.getHeight(),
                "Native reveal target exactly matches the original ScaleDrawable fill geometry");
        ViewFlipper body = card.findViewById(secondary ? R.id.widget_secondary_reveal_body : R.id.widget_reveal_body);
        ViewFlipper cap = card.findViewById(secondary ? R.id.widget_secondary_reveal_cap : R.id.widget_reveal_cap);
        View left = card.findViewById(secondary ? R.id.widget_secondary_reveal_left : R.id.widget_reveal_left);
        View capFill = cap.getCurrentView();
        int diameter = bar.getHeight();
        require(left.getWidth() == diameter && left.getHeight() == diameter
                        && capFill.getWidth() == diameter && capFill.getHeight() == diameter,
                "Both end caps keep the original bar's circular diameter");
        require(!overlay.isClickable() && !overlay.isFocusable(), "Reveal layers never consume widget taps");
        assertNativeAppearance(overlay);
        assertNativeTimesteps(body.getCurrentView(), body, false);
        assertNativeTimesteps(capFill, cap, true);
    }

    private static void assertNativeAppearance(ViewGroup overlay) {
        require(overlay instanceof ViewFlipper && ((ViewFlipper) overlay).getDisplayedChild() == 1,
                "The whole capsule shares one finite native appearance animation");
        View fill = ((ViewFlipper) overlay).getCurrentView();
        Animation original = fill.getAnimation();
        require(original != null && original.getDuration() == 160 && original.getRepeatCount() == 0,
                "Capsule appearance fades once over 160 ms without looping");
        Animation animation = AnimationUtils.loadAnimation(fill.getContext(), R.anim.widget_reveal_appear);
        animation.initialize(fill.getWidth(), fill.getHeight(), overlay.getWidth(), overlay.getHeight());
        animation.setStartTime(1000);
        Transformation transformation = new Transformation();
        for (int elapsed : new int[]{0, 80, 160, 980, 1180}) {
            transformation.clear();
            animation.getTransformation(1000 + elapsed, transformation);
            float alpha = transformation.getAlpha();
            if (elapsed == 0) require(alpha == 0f,
                    "The first native frame starts fully transparent, without a suddenly appearing cap");
            else if (elapsed < 160) require(alpha > 0f && alpha < 1f,
                    "The whole capsule becomes visible gradually during startup");
            else require(Math.abs(alpha - 1f) < .00001f,
                    "Finished appearance remains fully visible until and after the final host handoff");
            require(transformation.getMatrix().isIdentity(),
                    "Appearance changes opacity without scaling or moving the capsule");
        }
    }

    /** Exercise the host's continuous native interpolation at 60 and 120 Hz timestamps.
     * This checks frame-rate independence, not a claim about cloud emulator throughput. */
    private static void assertNativeTimesteps(View fill, ViewGroup parent, boolean cap) {
        Animation original = fill.getAnimation();
        require(original != null && original.getDuration() == REVEAL_DURATION_MS,
                "Displayed reveal child owns a finite 900 ms native animation");
        if (!cap) {
            float first = bodyScaleAt(fill, parent, .1f);
            float second = bodyScaleAt(fill, parent, .2f);
            float middle = bodyScaleAt(fill, parent, .5f);
            require(first > 0f && first <= .05f && second > first && second <= .15f,
                    "The first 10% and 20% of reveal time visibly ease in instead of rushing forward ["
                            + first + ", " + second + "]");
            require(middle >= .4f && middle <= .6f && middle > second,
                    "Gentle startup still advances to the middle of the capsule halfway through");
        }
        for (int refreshRate : new int[]{60, 120}) {
            Animation animation = AnimationUtils.loadAnimation(fill.getContext(),
                    cap ? R.anim.widget_reveal_cap : R.anim.widget_reveal_body);
            animation.reset();
            animation.initialize(fill.getWidth(), fill.getHeight(), parent.getWidth(), parent.getHeight());
            animation.setStartTime(1000);
            Transformation transform = new Transformation();
            float[] matrix = new float[9];
            int distinct = 0;
            float previous = -Float.MAX_VALUE;
            int samples = (int) Math.ceil(REVEAL_DURATION_MS * refreshRate / 1000d);
            for (int frame = 0; frame <= samples; frame++) {
                transform.clear();
                long time = 1000 + Math.min(REVEAL_DURATION_MS, Math.round(frame * 1000d / refreshRate));
                animation.getTransformation(time, transform);
                transform.getMatrix().getValues(matrix);
                float position = matrix[cap ? Matrix.MTRANS_X : Matrix.MSCALE_X];
                require(position >= previous - .00001f, "Native interpolation advances monotonically without overshoot");
                if (position != previous) distinct++;
                if (cap) require(Math.abs(matrix[Matrix.MSCALE_X] - 1f) < .00001f
                                && Math.abs(matrix[Matrix.MSCALE_Y] - 1f) < .00001f,
                        "Moving cap translates without scaling or squaring its circular edge");
                else require(position >= 0f && position <= 1f, "Native body stays inside the true quota extent");
                previous = position;
            }
            require(distinct >= samples - 1,
                    "Native animation supplies distinct positions at " + refreshRate + " Hz, without a low-rate step table");
            require(Math.abs(previous - (cap ? 0f : 1f)) < .00001f, "Native animation finishes at the exact original capsule");
        }
    }

    private static float bodyScaleAt(View fill, ViewGroup parent, float elapsedFraction) {
        Animation animation = AnimationUtils.loadAnimation(fill.getContext(), R.anim.widget_reveal_body);
        animation.initialize(fill.getWidth(), fill.getHeight(), parent.getWidth(), parent.getHeight());
        animation.setStartTime(1000);
        Transformation transformation = new Transformation();
        animation.getTransformation(1000 + Math.round(REVEAL_DURATION_MS * elapsedFraction), transformation);
        float[] matrix = new float[9];
        transformation.getMatrix().getValues(matrix);
        return matrix[Matrix.MSCALE_X];
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

    /** A launcher may inflate RemoteViews on a worker, then reapply them on the UI
     * thread. ProgressBar retains its construction thread and can defer drawable
     * levels even when getProgress() already reports the new value. Inspect and
     * draw the handoff inside the same UI task, before the queue can hide a gap. */
    private static void assertWorkerInflationHandoff(Instrumentation instrumentation,
            Context app, File previews) throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ExecutorService inflater = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "Quotile worker widget inflater"));
        int cases = 0;
        try {
            for (boolean dark : new boolean[]{false, true}) {
                for (int[] size : new int[][]{{350, 64, 0}, {250, 235, 0}, {350, 180, 1}}) {
                    int width = size[0], height = size[1];
                    boolean dual = size[2] != 0, saveEvidence = !dark && height == 64;
                    WidgetState state = state(75d, dual ? 42d : null);
                    RemoteViews settled = WidgetRenderer.remoteViews(app, width, height,
                            state, dark, false, true, false);
                    RemoteViews legacy = WidgetRenderer.remoteViews(app, width, height,
                            state, dark, false, true, true);
                    // Reproduce the previous release's exact empty-underlay policy.
                    int accent = Color.parseColor(dark ? "#ECECEC" : "#282828");
                    for (int id : visibleBarIds(dual)) {
                        legacy.setProgressBar(id, 10000, 0, false);
                        legacy.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(accent));
                    }
                    workerInflateAndAttach(instrumentation, activity, app, legacy,
                            inflater, width, height, card -> {
                                for (int id : visibleBarIds(dual))
                                    require(fillLevel(card.findViewById(id)) == 0,
                                            "Legacy worker-inflated reveal starts with an empty drawable");
                                settled.reapply(app, card);
                                layout(card, width, height);
                                for (int id : visibleBarIds(dual)) {
                                    ProgressBar bar = card.findViewById(id);
                                    int target = id == R.id.widget_progress ? 7500 : 4200;
                                    require(bar.getProgress() == target && fillLevel(bar) == 0,
                                            "Control reproduces deferred drawable level despite updated logical quota");
                                    Bitmap gap = draw(bar);
                                    require(fillEdge(gap, new Rect(0, 0, gap.getWidth(), gap.getHeight()), dark) == -1,
                                            "Legacy handoff really draws an empty capsule before its queued level update");
                                    gap.recycle();
                                }
                                if (saveEvidence) save(draw(card), previews, "flicker-legacy-immediate-light.png");
                            });

                    // Start again with a fresh worker-inflated hierarchy: the fix must
                    // work without relying on a previous widget's cached drawable.
                    ViewGroup card = workerInflateAndAttach(instrumentation, activity, app,
                            WidgetRenderer.remoteViews(app, width, height, state, dark, false, true, true),
                            inflater, width, height, fresh -> {
                                for (int id : visibleBarIds(dual)) {
                                    ProgressBar bar = fresh.findViewById(id);
                                    int target = id == R.id.widget_progress ? 7500 : 4200;
                                    require(bar.getProgress() == target && fillLevel(bar) == target,
                                            "Fresh worker-inflated reveal prewarms the actual drawable to its true quota");
                                    require(Color.alpha(bar.getProgressTintList().getDefaultColor()) == 0,
                                            "Prewarmed quota stays invisible behind the advancing native overlay");
                                }
                            });
                    // The native animation must keep its final fill even if the
                    // launcher's final RemoteViews delivery arrives after 900 ms.
                    SystemClock.sleep(1100);
                    int[] heldEdges = new int[visibleBarIds(dual).length];
                    onMain(instrumentation, () -> {
                        Bitmap held = draw(card);
                        int index = 0;
                        for (int id : visibleBarIds(dual)) {
                            ProgressBar bar = card.findViewById(id);
                            heldEdges[index++] = assertCapsuleFrame(held, boundsIn(card, bar), dark);
                        }
                        if (saveEvidence) save(held, previews, "flicker-reveal-held-light.png");
                        else held.recycle();
                    });
                    SystemClock.sleep(80);
                    onMain(instrumentation, () -> {
                        Bitmap before = draw(card);
                        int index = 0;
                        for (int id : visibleBarIds(dual)) {
                            ProgressBar bar = card.findViewById(id);
                            require(assertCapsuleFrame(before, boundsIn(card, bar), dark) == heldEdges[index++],
                                    "Finished overlay remains filled while final host delivery is delayed");
                        }
                        before.recycle();
                        // No waitForIdle, sleep, posted callback or next frame may
                        // intervene between this reapply and the pixel assertion.
                        settled.reapply(app, card);
                        layout(card, width, height);
                        Bitmap immediate = draw(card);
                        index = 0;
                        for (int id : visibleBarIds(dual)) {
                            ProgressBar bar = card.findViewById(id);
                            int target = id == R.id.widget_progress ? 7500 : 4200;
                            require(bar.getProgress() == target && fillLevel(bar) == target,
                                    "Settling retains the prewarmed drawable immediately, without queued zero-to-quota work");
                            require(Color.alpha(bar.getProgressTintList().getDefaultColor()) == 255,
                                    "Settling restores the original opaque capsule tint");
                            require(assertCapsuleFrame(immediate, boundsIn(card, bar), dark) == heldEdges[index++],
                                    "Immediate settled pixels preserve the filled extent and both round caps");
                        }
                        require(card.findViewById(R.id.widget_reveal).getVisibility() == View.GONE
                                        && card.findViewById(R.id.widget_secondary_reveal).getVisibility() == View.GONE,
                                "Immediate handoff removes the overlay only while a complete native capsule is ready");
                        if (saveEvidence) save(immediate, previews, "flicker-settled-immediate-light.png");
                        else immediate.recycle();
                    });
                    cases++;
                }
            }
            Bundle metrics = new Bundle();
            metrics.putString("stream", "FLICKER: " + cases
                    + " worker-inflated RemoteViews cases reproduce the old empty-drawable gap and verify"
                    + " an immediately filled handoff in the same UI task; light/dark compact, weekly and dual layouts;"
                    + " finished overlays remain filled during delayed final delivery.\n");
            instrumentation.sendStatus(0, metrics);
        } finally {
            inflater.shutdownNow();
            onMain(instrumentation, activity::finish);
        }
    }

    private static int[] visibleBarIds(boolean dual) {
        return dual ? new int[]{R.id.widget_progress, R.id.widget_secondary_progress}
                : new int[]{R.id.widget_progress};
    }

    private static int fillLevel(ProgressBar bar) {
        require(bar.getProgressDrawable() instanceof LayerDrawable,
                "Native quota bar uses its original layered drawable");
        Drawable fill = ((LayerDrawable) bar.getProgressDrawable()).findDrawableByLayerId(android.R.id.progress);
        require(fill != null, "Native quota drawable exposes its progress layer");
        return fill.getLevel();
    }

    private static ViewGroup workerInflateAndAttach(Instrumentation instrumentation,
            Activity activity, Context app, RemoteViews views, ExecutorService inflater,
            int width, int height, java.util.function.Consumer<ViewGroup> immediateAssertions) throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        ViewGroup[] card = new ViewGroup[1];
        Throwable[] failure = new Throwable[1];
        Handler main = new Handler(Looper.getMainLooper());
        // Use the public apply API on a dedicated worker to model launcher
        // construction off the UI thread, then perform attachment and reapply
        // through the main Handler. No hidden Android framework APIs are used.
        onMain(instrumentation, () -> inflater.execute(() -> {
            try {
                long constructionThread = Thread.currentThread().getId();
                require(constructionThread != Looper.getMainLooper().getThread().getId(),
                        "RemoteViews and its ProgressBars are actually constructed on a worker thread");
                View view = views.apply(app, new FrameLayout(app));
                main.post(() -> {
                        try {
                            require(Looper.myLooper() == Looper.getMainLooper()
                                            && Thread.currentThread().getId() != constructionThread,
                                    "Worker-inflated views attach and reapply on a different, UI thread");
                            card[0] = (ViewGroup) view;
                            FrameLayout root = new FrameLayout(activity);
                            root.addView(view, new FrameLayout.LayoutParams(dp(app, width), dp(app, height)));
                            activity.setContentView(root);
                            layout(view, width, height);
                            require(view.isAttachedToWindow(), "Worker-inflated widget attaches to a real activity window");
                            immediateAssertions.accept(card[0]);
                        } catch (Throwable error) { failure[0] = error; }
                        finally { applied.countDown(); }
                });
            } catch (Throwable error) {
                failure[0] = error;
                applied.countDown();
            }
        }));
        require(applied.await(5, TimeUnit.SECONDS), "Worker widget inflation completes promptly");
        if (failure[0] != null) throw new AssertionError("Worker widget handoff: " + failure[0].getMessage(), failure[0]);
        return card[0];
    }

    private static void assertAttachedSpinner(Instrumentation instrumentation, Context app, File previews)
            throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ViewGroup[] card = new ViewGroup[1];
        try {
            for (boolean dark : new boolean[]{false, true}) {
                RectF[] idleGlyph = new RectF[1];
                onMain(instrumentation, () -> {
                    card[0] = (ViewGroup) WidgetRenderer.remoteViews(app, 350, 180,
                            state(75d, 42d), dark, false, true).apply(app, new FrameLayout(app));
                    layout(card[0], 350, 180);
                    ImageButton idle = card[0].findViewById(R.id.widget_refresh);
                    // ImageView may scale its intrinsic vector through an image matrix.
                    idleGlyph[0] = new RectF(idle.getDrawable().getBounds());
                    idle.getImageMatrix().mapRect(idleGlyph[0]);
                    idleGlyph[0].offset(idle.getLeft() + idle.getPaddingLeft(),
                            idle.getTop() + idle.getPaddingTop());
                    WidgetRenderer.remoteViews(app, 350, 180, state(75d, 42d), dark, true, true)
                            .reapply(app, card[0]);
                    layout(card[0], 350, 180);
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
                    RectF spinningGlyph = new RectF(spinner.getIndeterminateDrawable().getBounds());
                    spinningGlyph.offset(spinner.getLeft() + spinner.getPaddingLeft(),
                            spinner.getTop() + spinner.getPaddingTop());
                    require(Math.abs(idleGlyph[0].left - spinningGlyph.left) <= .5f
                                    && Math.abs(idleGlyph[0].top - spinningGlyph.top) <= .5f
                                    && Math.abs(idleGlyph[0].right - spinningGlyph.right) <= .5f
                                    && Math.abs(idleGlyph[0].bottom - spinningGlyph.bottom) <= .5f,
                            "Idle and spinning arrows occupy the same exact image bounds [idle="
                                    + idleGlyph[0] + ", spinning=" + spinningGlyph + "]");
                    require(bounds(refresh).equals(bounds(spinner))
                                    && refresh.getPaddingLeft() == spinner.getPaddingLeft()
                                    && refresh.getPaddingTop() == spinner.getPaddingTop(),
                            "Tapping starts rotation without changing button size or glyph padding");
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
        FrameProbe probe = new FrameProbe();
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
            // Drain initial provider/options delivery before counting refresh publications.
            SystemClock.sleep(400);
            instrumentation.waitForIdleSync();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            onMain(instrumentation, () -> {
                card[0].recording = true;
                card[0].frames.clear();
                card[0].revealDelivered = new CountDownLatch(1);
                card[0].settledDelivered = new CountDownLatch(1);
                probe.start();
                WidgetMotion.reveal(app, store.generation(), () -> {
                    callbacks.incrementAndGet(); completed.countDown();
                });
                require(WidgetMotion.isRunning(), "Successful refresh starts a finite reveal for bound widgets");
            });
            require(card[0].revealDelivered.await(2, TimeUnit.SECONDS),
                    "Actual AppWidgetService must deliver the native reveal layout");
            // Wait for the 160 ms appearance fade; subsequent captures measure
            // motion while the entire capsule is opaque and still expanding.
            SystemClock.sleep(200);
            Bitmap early = screenshot(instrumentation, card[0]);
            SystemClock.sleep(120);
            Bitmap later = screenshot(instrumentation, card[0]);
            onMain(instrumentation, () -> {
                ProgressBar first = card[0].findViewById(R.id.widget_progress);
                Rect bar = boundsIn(card[0], first);
                int earlyEdge = assertCapsuleFrame(early, bar, false);
                int laterEdge = assertCapsuleFrame(later, bar, false);
                require(laterEdge > earlyEdge + 2,
                        "Actual hardware-rendered capsule must advance between elapsed native frames ["
                                + earlyEdge + " -> " + laterEdge + "]");
            });
            require(completed.await(3, TimeUnit.SECONDS), "Reveal must finish promptly and release its callback");
            require(card[0].settledDelivered.await(2, TimeUnit.SECONDS),
                    "AppWidgetService delivers the final accurate quota after native motion");
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                probe.stop();
                require(card[0].frames.size() == 2,
                        "AppWidgetService receives only reveal start and final data, never per-frame IPC [updates="
                                + card[0].frames.size() + "]");
                require(Arrays.equals(card[0].frames.get(0), new int[]{6800, 8400, 0, 0})
                                && Arrays.equals(card[0].frames.get(1), new int[]{6800, 8400, 255, 255}),
                        "Host receives prewarmed transparent fills followed by the same precise opaque quotas");
                ProgressBar second = card[0].findViewById(R.id.widget_secondary_progress);
                require(second != null && second.getProgress() == 8400, "Final dual quota is accurate in the host");
                require(!WidgetMotion.isRunning() && callbacks.get() == 1,
                        "Finite reveal ends with full data and a single completion callback");
                require(probe.times.size() >= 8,
                        "Launcher host receives display frame callbacks throughout the native reveal");
            });
            Bundle metrics = new Bundle();
            metrics.putString("stream", "MOTION: native AppWidgetHost publications=" + card[0].frames.size()
                    + "; display=" + activity.getDisplay().getRefreshRate() + " Hz; Choreographer callbacks="
                    + probe.times.size() + "; median frame interval=" + probe.medianIntervalMs()
                    + " ms. Continuous native transforms verified at 60 and 120 Hz sample times;"
                    + " emulator timings are measurements, not a device frame-rate guarantee.\n");
            instrumentation.sendStatus(0, metrics);
            save(early, previews, "motion-host-elapsed-early.png");
            save(later, previews, "motion-host-elapsed-later.png");
            save(screenshot(instrumentation, card[0]), previews, "motion-host-completed.png");

            AtomicInteger cancelledCallbacks = new AtomicInteger();
            onMain(instrumentation, () -> {
                WidgetMotion.reveal(app, store.generation(), cancelledCallbacks::incrementAndGet);
                require(WidgetMotion.isRunning(), "Second reveal starts");
            });
            SystemClock.sleep(100);
            onMain(instrumentation, WidgetMotion::cancel);
            SystemClock.sleep(1050);
            onMain(instrumentation, () -> {
                require(!WidgetMotion.isRunning() && cancelledCallbacks.get() == 1,
                        "Cancellation settles once and leaves no reveal loop running");
                require(((ProgressBar) card[0].findViewById(R.id.widget_progress)).getProgress() == 6800,
                        "Cancelling an animation restores true saved usage");
            });
            require(!QuotaSync.isRunning(), "Synthetic reveal must not start a network read");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),
                    "Transient widget motion must not schedule background work");
        } finally {
            onMain(instrumentation, () -> {
                probe.stop();
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
        CountDownLatch revealDelivered;
        CountDownLatch settledDelivered;
        RecordingView(Context context) { super(context); }
        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            ProgressBar first = findViewById(R.id.widget_progress);
            ProgressBar second = findViewById(R.id.widget_secondary_progress);
            if (recording && first != null && second != null) {
                frames.add(new int[]{first.getProgress(), second.getProgress(),
                        Color.alpha(first.getProgressTintList().getDefaultColor()),
                        Color.alpha(second.getProgressTintList().getDefaultColor())});
                View reveal = findViewById(R.id.widget_reveal);
                if (revealDelivered != null && reveal != null && reveal.getVisibility() == View.VISIBLE)
                    revealDelivered.countDown();
                if (settledDelivered != null && reveal != null && reveal.getVisibility() == View.GONE
                        && first.getProgress() == 6800 && second.getProgress() == 8400)
                    settledDelivered.countDown();
            }
        }
    }

    private static final class FrameProbe implements Choreographer.FrameCallback {
        final List<Long> times = new ArrayList<>();
        boolean active;
        void start() { active = true; Choreographer.getInstance().postFrameCallback(this); }
        void stop() { active = false; Choreographer.getInstance().removeFrameCallback(this); }
        @Override public void doFrame(long frameTimeNanos) {
            if (!active) return;
            times.add(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
        double medianIntervalMs() {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) intervals.add(times.get(i) - times.get(i - 1));
            Collections.sort(intervals);
            return intervals.get(intervals.size() / 2) / 1000000d;
        }
    }
    private static Rect boundsIn(ViewGroup root, View child) {
        Rect area = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, area);
        return area;
    }
    private static int assertCapsuleFrame(Bitmap bitmap, Rect bar, boolean dark) {
        int edge = fillEdge(bitmap, bar, dark), middle = bar.top + bar.height() / 2;
        require(edge > bar.left + 2, "Actual native reveal frame contains a visible capsule");
        int inset = Math.max(1, bar.height() / 8), top = bar.top + Math.max(0, bar.height() / 10);
        require(isFill(bitmap.getPixel(edge - inset, middle), dark)
                        && !isFill(bitmap.getPixel(edge - inset, top), dark)
                        && !isFill(bitmap.getPixel(bar.left + inset, top), dark),
                "Actual moving fill retains both circular caps instead of a vertical clipped edge");
        return edge;
    }

    private static int fillEdge(Bitmap bitmap, Rect bar, boolean dark) {
        int edge = -1, middle = bar.top + bar.height() / 2;
        for (int x = bar.left; x < bar.right; x++)
            if (isFill(bitmap.getPixel(x, middle), dark)) edge = x;
        return edge;
    }

    private static WidgetState state(Double weekly, Double secondary) {
        WidgetState state = QuotaStore.demoState();
        state.demo = false;
        state.weeklyRemaining = weekly;
        state.fiveHourRemaining = secondary;
        return state;
    }
    private static int expected(Double amount) {
        return amount == null ? 0 : (int) Math.round(amount * 100);
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
