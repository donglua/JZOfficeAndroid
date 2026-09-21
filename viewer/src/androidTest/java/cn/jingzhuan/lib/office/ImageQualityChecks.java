package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ImageQualityChecks {
    private static final int BIG = 2000, LOGO = 16;
    private static final long MAX_PIXELS = 8_000_000L, TIMEOUT_MS = 15_000;
    private static final String BIG_1 = "ppt/media/big1.png", BIG_2 = "ppt/media/big2.png", BIG_3 = "ppt/media/big3.png";
    private static final String LOGO_1 = "ppt/media/logo1.png", LOGO_2 = "ppt/media/logo2.png";
    private static final String NEAR_LIMIT = "ppt/media/near-limit.png";

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "Run image quality checks on the instrumentation thread");
        File fixture = fixture(activity.getCacheDir());
        AtomicReference<OfficeImages> holder = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        OfficePackage source = OfficePackage.open(activity, Uri.fromFile(fixture));
        try {
            instrumentation.runOnMainChecked(() -> holder.set(new OfficeImages(source, () -> {
                try { snapshot(holder.get()); }
                catch (AssertionError failure) { error.set(new IOException("Cache budget check failed", failure)); }
            }, new OfficePreviewView.Listener() {
                public void onLoaded(OfficePreviewView.Info info) { }
                public void onError(Exception failure) { error.set(failure); }
            })));
            OfficeImages images = holder.get();
            State one = request(instrumentation, images, error, set(BIG_1, LOGO_1, LOGO_2));
            check(one.full(BIG_1) && one.size(LOGO_1) == LOGO && one.size(LOGO_2) == LOGO, "Single large image and logos keep source resolution");

            State transition = transition(instrumentation, images, error, set(BIG_1, BIG_2, BIG_3, LOGO_1, LOGO_2));
            check(transition.sizes.containsKey(BIG_1), "Visible bitmap remains published while a lower-budget replacement decodes");

            State many = request(instrumentation, images, error, set(BIG_1, BIG_2, BIG_3, LOGO_1, LOGO_2));
            check(many.full(BIG_1), "First large image keeps source resolution when total source pixels exceed the budget");
            String sampled = sampled(many, BIG_2, BIG_3);
            check(sampled != null, "At least one lower-priority large image is sampled");

            State upgraded = request(instrumentation, images, error, set(sampled, LOGO_1, LOGO_2));
            check(upgraded.full(sampled), "A previously sampled image upgrades when requested with spare budget");

            State baseline = request(instrumentation, images, error, set(BIG_1, BIG_2, BIG_3, LOGO_1, LOGO_2));
            String reorderTarget = sampled(baseline, BIG_2, BIG_3);
            check(reorderTarget != null, "Baseline full-set request leaves a lower-priority image sampled");
            check(request(instrumentation, images, error, reordered(reorderTarget)).full(reorderTarget), "Reordering the same image set upgrades the new first image");

            request(instrumentation, images, error, set(NEAR_LIMIT));
            State crowded = transition(instrumentation, images, error, set(NEAR_LIMIT, BIG_2, BIG_3));
            check(crowded.sizes.containsKey(NEAR_LIMIT), "Near-limit visible bitmap survives an increased visible image count");
            request(instrumentation, images, error, set(NEAR_LIMIT, BIG_2, BIG_3));

            instrumentation.runOnMainChecked(() -> {
                images.suspend();
                State suspended = snapshot(images);
                check(suspended.sizes.isEmpty() && images.wanted.isEmpty() && !images.loading, "Suspend clears cached and wanted images");
                images.resume();
            });
            State resumed = request(instrumentation, images, error, set(BIG_3, LOGO_1, LOGO_2));
            check(resumed.full(BIG_3) && resumed.size(LOGO_1) == LOGO && resumed.size(LOGO_2) == LOGO, "Resume allows images to reload at source resolution");
            return "IMAGE QUALITY CHECKS\n"
                + "PASS visible bitmaps survive budget transitions and new entries stay within the 8M cache budget\n"
                + "PASS bounded cache preserves priority image quality, upgrades with spare budget, honors reorder priority, and reloads after suspend\n"
                + "IMAGE QUALITY CHECKS PASSED\n";
        } finally {
            try {
                OfficeImages images = holder.get();
                if (images != null) instrumentation.runOnMainChecked(images::close);
                else source.close();
            } finally { fixture.delete(); }
        }
    }

    private static State request(OfficeInstrumentation instrumentation, OfficeImages images, AtomicReference<Exception> error, LinkedHashSet<String> parts) throws Exception {
        instrumentation.runOnMainChecked(() -> images.request(parts));
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        AtomicReference<State> ready = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainChecked(() -> {
                if (error.get() != null) throw new AssertionError("Image decode failed", error.get());
                State state = snapshot(images);
                if (!images.loading && images.bitmaps.keySet().equals(parts)) {
                    ready.set(state);
                }
            });
            if (ready.get() != null) return ready.get();
            SystemClock.sleep(20);
        }
        throw new AssertionError("Timed out waiting for images: " + parts);
    }

    private static State transition(OfficeInstrumentation instrumentation, OfficeImages images, AtomicReference<Exception> error, LinkedHashSet<String> parts) throws Exception {
        AtomicReference<State> state = new AtomicReference<>();
        instrumentation.runOnMainChecked(() -> { images.request(parts); state.set(snapshot(images)); });
        return state.get();
    }

    private static State snapshot(OfficeImages images) {
        long pixels = 0;
        Map<String, int[]> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, Bitmap> entry : images.bitmaps.entrySet()) {
            Bitmap bitmap = entry.getValue();
            check(bitmap != null && !bitmap.isRecycled(), "Cached bitmap is live: " + entry.getKey());
            pixels += (long) bitmap.getWidth() * bitmap.getHeight();
            sizes.put(entry.getKey(), new int[] {bitmap.getWidth(), bitmap.getHeight()});
        }
        check(pixels <= MAX_PIXELS, "Cached pixels stay within the 8M budget: " + pixels);
        return new State(sizes);
    }

    private static LinkedHashSet<String> reordered(String first) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        parts.add(first);
        for (String part : Arrays.asList(BIG_1, BIG_2, BIG_3, LOGO_1, LOGO_2)) parts.add(part);
        return parts;
    }

    private static String sampled(State state, String... parts) {
        for (String part : parts) if (!state.full(part)) return part;
        return null;
    }
    private static LinkedHashSet<String> set(String... parts) { return new LinkedHashSet<>(Arrays.asList(parts)); }
    private static final class State {
        final Map<String, int[]> sizes;
        State(Map<String, int[]> sizes) { this.sizes = sizes; }
        int size(String part) { return sizes.get(part)[0]; }
        boolean full(String part) {
            int[] size = sizes.get(part);
            return size != null && size[0] == BIG && size[1] == BIG;
        }
    }
    private static File fixture(File directory) throws IOException {
        File file = File.createTempFile("image-quality-", ".zip", directory);
        boolean complete = false;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rDoc\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>Image quality fixture</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            image(zip, BIG_1, BIG, Color.RED);
            image(zip, BIG_2, BIG, Color.GREEN);
            image(zip, BIG_3, BIG, Color.BLUE);
            image(zip, LOGO_1, LOGO, Color.YELLOW);
            image(zip, LOGO_2, LOGO, Color.CYAN);
            image(zip, NEAR_LIMIT, 2800, Color.MAGENTA);
            complete = true;
        } finally {
            if (!complete) file.delete();
        }
        return file;
    }

    private static void image(ZipOutputStream zip, String name, int size, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            zip.putNextEntry(new ZipEntry(name));
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip), "Encode fixture image: " + name);
            zip.closeEntry();
        } finally { bitmap.recycle(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
