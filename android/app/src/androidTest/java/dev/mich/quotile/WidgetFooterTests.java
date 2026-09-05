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

/** Native regression checks for the mixed Chinese/date footer reported on Fold7. */
final class WidgetFooterTests {
    private static final String MIXED_FOOTER = "9/7 13:07 重置 · 19:00 更新";

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    static void run(Instrumentation instrumentation, Context app, File previews) {
        for (float scale : new float[]{1f, 1.3f, 2f})
            renderCase(instrumentation, app, previews, 350, 64, scale, false);
        for (int height : new int[]{142, 150, 180})
            for (float scale : new float[]{1f, 1.3f})
                for (boolean dual : new boolean[]{false, true})
                    renderCase(instrumentation, app, previews, 350, height, scale, dual);
        // Extremely narrow cards may abbreviate horizontally, but must keep line height intact.
        for (float scale : new float[]{1f, 2f})
            renderCase(instrumentation, app, previews, 110, 64, scale, false);
    }

    private static void renderCase(Instrumentation instrumentation, Context app, File previews,
            int widthDp, int heightDp, float fontScale, boolean dual) {
        String name = "footer-" + widthDp + "x" + heightDp + "-font-" + fontScale
                + (dual ? "-dual" : "-weekly");
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                Configuration config = new Configuration(app.getResources().getConfiguration());
                config.fontScale = fontScale;
                Context context = app.createConfigurationContext(config);
                WidgetState snapshot = new WidgetState();
                snapshot.configured = true;
                snapshot.weeklyRemaining = 84d;
                snapshot.updatedAt = System.currentTimeMillis() / 1000L;
                snapshot.weeklyResetAt = snapshot.updatedAt + 2 * 86400;
                if (dual) {
                    snapshot.fiveHourRemaining = 72d;
                    snapshot.fiveHourResetAt = snapshot.updatedAt + 3600;
                }
                ViewGroup card = (ViewGroup) WidgetRenderer.remoteViews(context,
                        widthDp, heightDp, snapshot, false, false)
                        .apply(context, new FrameLayout(context));
                TextView status = card.findViewById(R.id.widget_status);
                require(Math.abs(status.getResources().getConfiguration().fontScale - fontScale) < .01f,
                        "RemoteViews must be inflated at the requested font scale");
                require(status.getText().toString().contains("更新"), "Use a real-data footer, not demo text");
                if (widthDp >= 250) {
                    // Keep the exact user's mixed-script example independent of the test run's date.
                    status.setText(MIXED_FOOTER);
                }
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(widthDp * density);
                int height = Math.round(heightDp * density);
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                card.layout(0, 0, width, height);
                int visibleFooters = 0;
                Rect[] footerBoxes = new Rect[3];
                Rect bar = bounds(card, card.findViewById(R.id.widget_progress));
                for (int id : new int[]{R.id.widget_reset, R.id.widget_secondary_reset, R.id.widget_status}) {
                    TextView footer = card.findViewById(id);
                    if (footer.getVisibility() != View.VISIBLE) continue;
                    assertLineFits(footer, widthDp >= 250, "Footer " + id);
                    Rect footerBounds = bounds(card, footer);
                    for (int previous = 0; previous < visibleFooters; previous++)
                        require(!Rect.intersects(footerBoxes[previous], footerBounds),
                                "Reset and update footer rows must not overlap");
                    footerBoxes[visibleFooters++] = footerBounds;
                    require(footerBounds.top >= bar.bottom, "Footer must not overlap the remaining bar");
                    require(footerBounds.bottom <= card.getHeight() - Math.round(density) + 1,
                            "Footer must leave space before the card's lower edge");
                }
                require(visibleFooters > 0, "The footer must remain visible at this height");
                if (widthDp >= 250) {
                    TextView label = card.findViewById(R.id.widget_label);
                    ImageView icon = card.findViewById(R.id.widget_source_icon);
                    require(label.getVisibility() == View.VISIBLE
                                    && label.getText().toString().contains("Codex")
                                    && label.getText().toString().contains("额度"),
                            "The widget identifies its Codex quota source");
                    assertLineFits(label, true, "Codex source label");
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
                Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                try {
                    image.setDensity(context.getResources().getDisplayMetrics().densityDpi);
                    card.draw(new Canvas(image));
                    try (FileOutputStream output = new FileOutputStream(new File(previews, name + ".png"))) {
                        require(image.compress(Bitmap.CompressFormat.PNG, 100, output), "Save native footer preview");
                    }
                } finally { image.recycle(); }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null)
            throw new AssertionError(name + ": " + failure[0].getMessage(), failure[0]);
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
