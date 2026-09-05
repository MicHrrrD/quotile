package dev.mich.quotile;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Native checks for full CJK lines and the available-reset row on Fold7-sized cards. */
final class WidgetFooterTests {
    private static final String MIXED_FOOTER = "9/7 13:07 重置 · 19:00 更新";
    private enum Mode { NORMAL, STALE, REFRESHING, DEMO }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    static void run(Instrumentation instrumentation, Context app, File previews) {
        // Keep the original mixed-script footer regression and the compact layout unchanged.
        for (float scale : new float[]{1f, 1.3f, 2f})
            renderCase(instrumentation, app, previews, 350, 64, scale, false, 2L,
                    Mode.NORMAL, true, true);
        for (float scale : new float[]{1f, 2f})
            renderCase(instrumentation, app, previews, 110, 64, scale, false, 2L,
                    Mode.NORMAL, true, false);

        // Exercise each footer/spacing boundary, with both quota-window layouts.
        for (int height : new int[]{110, 134, 142, 150, 159, 160, 180, 300})
            for (float scale : new float[]{1f, 1.3f})
                for (boolean dual : new boolean[]{false, true})
                    renderCase(instrumentation, app, previews, 350, height, scale, dual, 2L,
                            Mode.NORMAL, true, scale == 1.3f && (height == 110 || height == 160));
        for (int height : new int[]{110, 160})
            for (boolean dual : new boolean[]{false, true})
                renderCase(instrumentation, app, previews, 350, height, 2f, dual, 2L,
                        Mode.NORMAL, true, false);
        // Narrow widgets can abbreviate horizontally, but must retain complete line height.
        for (int width : new int[]{110, 220, 250})
            for (int height : new int[]{110, 160})
                renderCase(instrumentation, app, previews, width, height, 2f, true, 2L,
                        Mode.NORMAL, false, false);

        for (int height : new int[]{110, 180})
            for (Long count : new Long[]{0L, null})
                renderCase(instrumentation, app, previews, 350, height, 1.3f, false, count,
                        Mode.NORMAL, false, false);
        for (int height : new int[]{150, 180})
            for (Mode mode : new Mode[]{Mode.STALE, Mode.REFRESHING, Mode.DEMO})
                renderCase(instrumentation, app, previews, 350, height, 1.3f, true, 2L,
                        mode, false, false);

        // These two previews use actual renderer text, with no fixed-footer substitution.
        for (boolean dual : new boolean[]{false, true})
            renderCase(instrumentation, app, previews, 350, 180, 1f, dual, 2L,
                    Mode.NORMAL, false, true);
    }

    private static void renderCase(Instrumentation instrumentation, Context app, File previews,
            int widthDp, int heightDp, float fontScale, boolean dual, Long count,
            Mode mode, boolean mixedFooter, boolean savePreview) {
        String name = (mixedFooter ? "footer-" : "reset-count-") + widthDp + "x" + heightDp
                + "-font-" + fontScale + (dual ? "-dual" : "-weekly")
                + (mode == Mode.NORMAL ? "" : "-" + mode.name().toLowerCase(java.util.Locale.ROOT))
                + (count == null ? "-unknown" : count == 0L ? "-zero" : "");
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                Configuration config = new Configuration(app.getResources().getConfiguration());
                config.fontScale = fontScale;
                Context context = app.createConfigurationContext(config);
                WidgetState snapshot = new WidgetState();
                snapshot.configured = true;
                snapshot.weeklyRemaining = 84d;
                snapshot.availableResetCount = count;
                snapshot.updatedAt = System.currentTimeMillis() / 1000L;
                snapshot.weeklyResetAt = snapshot.updatedAt + 2 * 86400;
                snapshot.stale = mode == Mode.STALE;
                snapshot.demo = mode == Mode.DEMO;
                if (dual) {
                    snapshot.fiveHourRemaining = 72d;
                    snapshot.fiveHourResetAt = snapshot.updatedAt + 3600;
                }
                ViewGroup card = (ViewGroup) WidgetRenderer.remoteViews(context,
                        widthDp, heightDp, snapshot, false, mode == Mode.REFRESHING)
                        .apply(context, new FrameLayout(context));
                TextView status = card.findViewById(R.id.widget_status);
                TextView resetCount = card.findViewById(R.id.widget_reset_count);
                require(Math.abs(status.getResources().getConfiguration().fontScale - fontScale) < .01f,
                        "RemoteViews must be inflated at the requested font scale");
                assertContent(card, heightDp, count, mode);
                if (mixedFooter && widthDp >= 250 && status.getVisibility() == View.VISIBLE) {
                    // Keep the user's mixed-script example independent of the test run's date.
                    status.setText(MIXED_FOOTER);
                }
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(widthDp * density);
                int height = Math.round(heightDp * density);
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                card.layout(0, 0, width, height);
                int visibleFooters = 0;
                Rect[] footerBoxes = new Rect[4];
                Rect bar = bounds(card, card.findViewById(R.id.widget_progress));
                for (int id : new int[]{R.id.widget_reset, R.id.widget_secondary_reset,
                        R.id.widget_reset_count, R.id.widget_status}) {
                    TextView footer = card.findViewById(id);
                    if (footer.getVisibility() != View.VISIBLE) continue;
                    assertLineFits(footer, widthDp >= 350, "Footer " + id);
                    Rect footerBounds = bounds(card, footer);
                    for (int previous = 0; previous < visibleFooters; previous++)
                        require(!Rect.intersects(footerBoxes[previous], footerBounds),
                                "Reset time, reset count and update footer rows must not overlap");
                    footerBoxes[visibleFooters++] = footerBounds;
                    require(footerBounds.top >= bar.bottom, "Footer must not overlap the remaining bar");
                    require(footerBounds.left >= 0 && footerBounds.right <= card.getWidth(),
                            "Footer must remain inside the card horizontally");
                    require(footerBounds.bottom <= card.getHeight() - Math.round(density) + 1,
                            "Footer must leave space before the card's lower edge");
                }
                require(visibleFooters > 0, "The footer must remain visible at this height");
                if (heightDp >= 110) {
                    Rect countBox = bounds(card, resetCount);
                    for (int id : new int[]{R.id.widget_reset, R.id.widget_secondary_reset}) {
                        View reset = card.findViewById(id);
                        if (reset.getVisibility() == View.VISIBLE)
                            require(countBox.top >= bounds(card, reset).bottom,
                                    "Available reset count must be below the reset time");
                    }
                    for (int id : new int[]{R.id.widget_value, R.id.widget_secondary_value}) {
                        TextView value = card.findViewById(id);
                        if (value.getVisibility() != View.VISIBLE) continue;
                        assertLineFits(value, true, "Remaining percentage");
                        require(bounds(card, value).bottom <= bar.top,
                                "Remaining percentage must not overlap the bar");
                    }
                }
                if (widthDp >= 250) {
                    TextView label = card.findViewById(R.id.widget_label);
                    ImageView icon = card.findViewById(R.id.widget_source_icon);
                    require(label.getVisibility() == View.VISIBLE
                                    && label.getText().toString().contains("Codex")
                                    && label.getText().toString().contains("额度"),
                            "The widget identifies its Codex quota source");
                    assertLineFits(label, widthDp >= 350, "Codex source label");
                    require(icon != null && icon.getVisibility() == View.VISIBLE && icon.getDrawable() != null,
                            "The source logo must be visible");
                    Rect logo = bounds(card, icon);
                    Rect labelBox = bounds(card, label);
                    Rect value = bounds(card, card.findViewById(R.id.widget_value));
                    require(logo.width() > 0 && logo.height() > 0 && logo.left >= 0 && logo.top >= 0,
                            "The logo must have a visible size and position");
                    require(!Rect.intersects(logo, labelBox) && !Rect.intersects(logo, value)
                                    && !Rect.intersects(labelBox, value),
                            "The logo, Codex label and remaining percentage must not overlap");
                }
                if (savePreview) {
                    Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        image.setDensity(context.getResources().getDisplayMetrics().densityDpi);
                        card.draw(new Canvas(image));
                        try (FileOutputStream output = new FileOutputStream(new File(previews, name + ".png"))) {
                            require(image.compress(Bitmap.CompressFormat.PNG, 100, output), "Save native footer preview");
                        }
                    } finally { image.recycle(); }
                }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null)
            throw new AssertionError(name + ": " + failure[0].getMessage(), failure[0]);
    }

    private static void assertContent(ViewGroup card, int heightDp, Long count, Mode mode) {
        TextView resetCount = card.findViewById(R.id.widget_reset_count);
        TextView status = card.findViewById(R.id.widget_status);
        boolean detailed = heightDp >= 110;
        boolean separateStatus = !detailed || heightDp >= 160;
        require((resetCount.getVisibility() == View.VISIBLE) == detailed,
                "Only widgets at least two rows high show available reset count");
        require((status.getVisibility() == View.VISIBLE) == separateStatus,
                "Short two-row widgets reserve their footer space for reset time and count");
        String indicator = mode == Mode.STALE ? "旧数据" : mode == Mode.REFRESHING ? "刷新"
                : mode == Mode.DEMO ? "演示" : "更新";
        if (separateStatus)
            require(status.getText().toString().contains(indicator),
                    "Visible status must retain the update/refresh/stale/demo indicator");
        if (!detailed) return;
        String expected = count == null ? "可用重置 —" : "可用重置 " + count + " 次";
        if (!separateStatus && mode != Mode.NORMAL)
            expected += mode == Mode.REFRESHING ? " · 刷新中" : " · " + indicator;
        require(resetCount.getText().toString().equals(expected),
                "Reset count distinguishes unavailable data, zero and positive counts, with pending status");
        TextView reset = card.findViewById(R.id.widget_reset);
        require(reset.getVisibility() == View.VISIBLE && reset.getText().toString().contains("重置"),
                "Reset time remains visible above the available count");
        require(card.findViewById(R.id.widget_refresh).isEnabled() == (mode != Mode.REFRESHING),
                "Refresh button is disabled only while a refresh is in progress");
    }

    private static void assertLineFits(TextView text, boolean requireFullText, String description) {
        Layout layout = text.getLayout();
        require(layout != null && layout.getLineCount() == 1, description + " must have one laid-out line");
        require(layout.getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom()
                        <= text.getHeight() + 1,
                description + " must fit its complete native line height, including font padding");
        if (requireFullText)
            require(layout.getEllipsisCount(0) == 0, description + " must be readable in full");
    }

    private static Rect bounds(ViewGroup card, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        card.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }
}
