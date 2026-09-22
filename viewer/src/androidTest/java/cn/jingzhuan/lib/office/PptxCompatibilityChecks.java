package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.FIXTURE;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.GREEN;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.IMAGE;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.ORANGE;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.TEAL;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.near;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class PptxCompatibilityChecks {
    private static final int WIDTH = 1080, HEIGHT = 800;

    private PptxCompatibilityChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        String log = PptxRenderingChecks.run(instrumentation);
        OfficePreviewView view = activity.preview;
        instrumentation.load(FIXTURE, "PPTX");
        int[] original = new int[4];
        instrumentation.runOnMainChecked(() -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            original[0] = params.width; original[1] = params.height;
            original[2] = view.getWidth(); original[3] = view.getHeight();
            params.width = WIDTH; params.height = HEIGHT; view.setLayoutParams(params);
            measure(view, WIDTH, HEIGHT);
            view.resetZoom(); view.jumpToPage(1);
        });
        try {
            awaitImages(instrumentation, view, Collections.singleton(IMAGE));
            instrumentation.runOnMainChecked(() -> viewPixels(view, true));
            instrumentation.runOnMainChecked(() -> {
                view.setZoom(3); view.jumpToPage(1);
                near(view.getZoom(), 3, 0.001f, "View zoom is applied");
            });
            awaitImages(instrumentation, view, Collections.singleton(IMAGE));
            instrumentation.runOnMainChecked(() -> viewPixels(view, false));
            instrumentation.runOnMainChecked(() -> view.jumpToPage(2));
            awaitImages(instrumentation, view, Collections.emptySet());
            instrumentation.runOnMainChecked(() -> check(view.getCurrentPage() == 2, "Jump selects slide two"));
            instrumentation.runOnMainChecked(() -> { view.resetZoom(); view.jumpToPage(1); });
            awaitImages(instrumentation, view, Collections.singleton(IMAGE));
            instrumentation.runOnMainChecked(() -> {
                near(view.getZoom(), 1, 0.001f, "Reset restores fit width");
                check(view.getCurrentPage() == 1, "Return selects first slide");
                viewPixels(view, true);
            });
        } finally {
            instrumentation.runOnMainChecked(() -> {
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = original[0]; params.height = original[1]; view.setLayoutParams(params);
                measure(view, original[2], original[3]);
                view.requestLayout();
            });
        }
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainChecked(() -> { view.resetZoom(); view.jumpToPage(1); });
        instrumentation.captureScreen("screen-pptx-compat-page1");
        instrumentation.runOnMainChecked(() -> { view.setZoom(3); view.jumpToPage(1); });
        instrumentation.captureScreen("screen-pptx-compat-zoom");
        instrumentation.runOnMainChecked(() -> view.jumpToPage(2));
        instrumentation.captureScreen("screen-pptx-compat-page2");
        return log + "PASS URI/view pixels, image cache, zoom and page jump at 1080x800\n"
            + "SCREENSHOTS screen-pptx-compat-page1.png, screen-pptx-compat-zoom.png, screen-pptx-compat-page2.png\n";
    }

    private static void measure(OfficePreviewView view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void awaitImages(OfficeInstrumentation instrumentation, OfficePreviewView view, Set<String> expected) throws Exception {
        Field field = OfficePreviewView.class.getDeclaredField("images"); field.setAccessible(true);
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainChecked(() -> {
                check(view.getWidth() == WIDTH && view.getHeight() == HEIGHT, "Stable 1080x800 test viewport");
                view.draw(new Canvas());
                OfficeImages images = (OfficeImages) field.get(view);
                if (images == null || images.loading || !images.wanted.equals(expected) || !images.bitmaps.keySet().containsAll(expected)) return;
                for (Bitmap bitmap : images.bitmaps.values()) {
                    check(bitmap != null && !bitmap.isRecycled(), "Expected image cache contains decoded pixels");
                    color(bitmap, 0, 0, TEAL, "Visible image cache holds fixture pixels");
                }
                ready.set(true);
            });
            if (ready.get()) return;
            SystemClock.sleep(20);
        }
        throw new AssertionError("Expected visible/cache image set did not settle: " + expected);
    }

    private static void viewPixels(OfficePreviewView view, boolean includeBar) {
        Bitmap frame = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(frame));
            float density = view.getResources().getDisplayMetrics().density;
            float scale = Math.max(1, view.getWidth() - 24 * density) / 720 * view.getZoom();
            float left = Math.max(0, (view.getWidth() - 720 * scale) / 2), top = 12 * density;
            color(frame, Math.round(left + 140 * scale), Math.round(top + 70 * scale), GREEN, "Real view renders transformed rectangle");
            color(frame, Math.round(left + 220 * scale), Math.round(top + 70 * scale), TEAL, "Real view renders loaded grouped picture");
            if (includeBar) color(frame, Math.round(left + 470 * scale), Math.round(top + 70 * scale), ORANGE, "Real view renders rotated bar");
        } finally { frame.recycle(); }
    }
}
