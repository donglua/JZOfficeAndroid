package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ImageCacheChecks {
    private static final String A = "word/media/a.png", B = "word/media/b.png", C = "word/media/c.png";
    private static final String BAD = "word/media/invalid.png";
    private static final long MAX_PIXELS = 8_000_000L;
    private static final int BIG = 2000, LOGO = 16, LOGOS = 36, SAMPLES = 10;
    private final OfficeInstrumentation instrumentation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private OfficeImages images;
    private ThreadPoolExecutor worker;
    private CountDownLatch settled;
    private Map<String, Bitmap> idleCache;
    private boolean startedLoading;
    private int failedImages;
    private long startNs, elapsedNs, peakPixels, peakBytes;

    private ImageCacheChecks(OfficeInstrumentation instrumentation) { this.instrumentation = instrumentation; }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "Run cache checks on the instrumentation thread");
        return new ImageCacheChecks(instrumentation).run(activity);
    }

    private String run(PreviewTestActivity activity) throws Exception {
        File fixture = fixture(activity.getCacheDir());
        try {
            OfficePackage source = OfficePackage.open(activity, Uri.fromFile(fixture));
            try {
                instrumentation.runOnMainChecked(() -> images = new OfficeImages(source, this::changed, new OfficePreviewView.Listener() {
                    public void onLoaded(OfficePreviewView.Info info) { }
                    public void onError(Exception error) {
                        failedImages++;
                        if (!images.wanted.contains(BAD)) failure.compareAndSet(null, error);
                    }
                }));
                Field field = OfficeImages.class.getDeclaredField("worker");
                field.setAccessible(true);
                worker = (ThreadPoolExecutor) field.get(images);
                reuseAndEviction();
                limitsAndLifecycle();
                return "IMAGE CACHE CHECKS\nPASS identity reuse without jobs/loading, LRU, upgrades and published bitmap lifetime\n"
                    + "PASS failure cleanup, entry limits, suspend/reload and 8M total published pixels at every observation\n"
                    + benchmark(false) + benchmark(true) + "IMAGE CACHE CHECKS PASSED\n";
            } finally {
                if (images == null) source.close();
                else instrumentation.runOnMainChecked(images::close);
                if (worker != null) check(worker.awaitTermination(15, TimeUnit.SECONDS), "Image worker closes its source");
            }
        } finally { fixture.delete(); }
    }

    private void reuseAndEviction() throws Exception {
        Bitmap a = request(A).get(A);
        Map<String, Bitmap> ab = request(B);
        Bitmap b = ab.get(B);
        check(full(a, BIG) && full(b, BIG) && ab.get(A) == a, "A and B retain both 2000x2000 originals at exactly 8M pixels");
        check(a.getPixel(0, 0) == Color.RED && b.getPixel(0, 0) == Color.GREEN, "Distinct image contents decode correctly");
        long jobs = worker.getTaskCount();
        check(request(A).get(A) == a && !startedLoading && worker.getTaskCount() == jobs, "A -> B -> A reuses the SAME bitmap without loading or a decode job");
        Map<String, Bitmap> ac = request(C);
        check(ac.get(A) == a && full(ac.get(C), BIG) && !ac.containsKey(B), "Touching A then loading C evicts least-recently-used B");
        check(ac.get(C).getPixel(0, 0) == Color.BLUE && !b.isRecycled(), "Evicted published B remains live");

        clear();
        Map<String, Bitmap> originals = request(A, logo(0), logo(1));
        check(full(originals.get(A), BIG), "Large image plus two tiny logos reaches source quality");
        jobs = worker.getTaskCount();
        request(logo(0));
        Map<String, Bitmap> restored = request(A, logo(0), logo(1));
        check(!startedLoading && worker.getTaskCount() == jobs, "Returning to an all-full cached set never downsizes then upgrades");
        for (String part : originals.keySet()) check(restored.get(part) == originals.get(part), "Full cached set preserves bitmap identity: " + part);

        clear();
        Map<String, Bitmap> crowded = request(A, B, C);
        Bitmap small = crowded.get(B);
        check(full(crowded.get(A), BIG) && !full(small, BIG), "Three visible pictures leave B sampled");
        request(A);
        Map<String, Bitmap> upgraded = request(B);
        check(full(upgraded.get(B), BIG) && upgraded.get(B) != small, "Returning to sampled cold B preserves its upgrade opportunity");
        check(upgraded.get(A) == crowded.get(A) && !upgraded.containsKey(C), "Visible upgrade reclaims cold C's budget and retains recently touched A");
        check(!small.isRecycled() && !crowded.get(C).isRecycled(), "Replaced and evicted published bitmaps are not recycled");
    }

    private void limitsAndLifecycle() throws Exception {
        clear();
        String[] logos = new String[LOGOS];
        for (int i = 0; i < LOGOS; i++) { logos[i] = logo(i); check(full(request(logos[i]).get(logos[i]), LOGO), "Tiny image stays full resolution"); }
        Map<String, Bitmap> cold = request();
        check(cold.size() == 32 && !cold.containsKey(logo(3)) && cold.containsKey(logo(4)), "Thirty-six visits retain the newest 32 cold images");
        Map<String, Bitmap> visible = request(logos);
        check(visible.size() == LOGOS, "More than 32 visible pictures are never evicted");
        for (String logo : logos) check(full(visible.get(logo), LOGO), "Every visible logo remains published");
        long jobs = worker.getTaskCount();
        Map<String, Bitmap> hit = request(logo(LOGOS - 1));
        check(hit.size() == 32 && hit.get(logo(LOGOS - 1)) == visible.get(logo(LOGOS - 1)) && !startedLoading && worker.getTaskCount() == jobs,
            "Thirty-six visible images shrink to 32 on a cached hit without decoding");
        check(request().size() == 32, "Once offscreen, the oversized visible set returns to 32 entries");

        clear();
        Map<String, Bitmap> invalid = request(BAD);
        check(invalid.containsKey(BAD) && invalid.get(BAD) == null && failedImages == 1, "Invalid image reports one failure and publishes a visible placeholder");
        jobs = worker.getTaskCount();
        request(BAD);
        check(worker.getTaskCount() == jobs && failedImages == 1, "Visible failure is not repeatedly decoded");
        check(!request(logo(0)).containsKey(BAD), "Failed null entry is purged once offscreen");
        request(BAD);
        check(failedImages == 2 && worker.getTaskCount() == jobs + 2, "Revisiting a purged failure retries exactly once");

        Bitmap before = request(A).get(A);
        jobs = worker.getTaskCount();
        clear();
        Bitmap after = request(A).get(A);
        check(after != before && full(after, BIG) && worker.getTaskCount() == jobs + 1, "Suspend/resume reloads one fresh full-resolution bitmap");
        check(!before.isRecycled(), "Suspend drops ownership without recycling a published bitmap");
    }

    private String benchmark(boolean cacheCleared) throws Exception {
        clear();
        for (int i = 0; i < 4; i++) { if (cacheCleared) clear(); request(i % 2 == 0 ? A : B); }
        instrumentation.runOnMainChecked(() -> { peakPixels = peakBytes = 0; snapshot(); });
        long jobs = worker.getTaskCount();
        long[] times = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            if (cacheCleared) clear();
            String part = i % 2 == 0 ? A : B;
            check(full(request(part).get(part), BIG), "Benchmark requests the same full-resolution image quality");
            check(cacheCleared || !startedLoading, "Warm alternating navigation never enters loading");
            times[i] = elapsedNs;
        }
        long decoded = worker.getTaskCount() - jobs;
        check(decoded == (cacheCleared ? SAMPLES : 0), "Measured decode jobs: " + decoded);
        check(peakPixels == (cacheCleared ? 4_000_000L : MAX_PIXELS), "Benchmark retained pixel accounting");
        Arrays.sort(times);
        return String.format(Locale.ROOT, "%s: request-to-idle median=%.3f ms, jobs=%d/%d requests, peak retained pixels=%d, peak retained allocation bytes=%d; 4 warmups, no timing threshold\n",
            cacheCleared ? "CACHE-CLEARED CONTROL (suspend/resume before each request; not an old binary)" : "RECENT-IMAGE RETENTION",
            (times[SAMPLES / 2 - 1] + times[SAMPLES / 2]) / 2_000_000.0, decoded, SAMPLES, peakPixels, peakBytes);
    }

    private Map<String, Bitmap> request(String... parts) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainChecked(() -> {
            settled = done;
            startNs = System.nanoTime();
            images.request(new LinkedHashSet<>(Arrays.asList(parts)));
            startedLoading = images.loading;
            idle();
        });
        check(done.await(15, TimeUnit.SECONDS), "Timed out waiting for cache: " + Arrays.toString(parts));
        if (failure.get() != null) throw new AssertionError("Image cache callback failed", failure.get());
        return idleCache;
    }

    private void changed() {
        try { snapshot(); } catch (AssertionError error) { failure.compareAndSet(null, error); }
        // Publication calls changed before loadNext; observe idle after that continuation runs.
        main.post(this::idle);
    }

    private void idle() {
        if (settled == null || settled.getCount() == 0) return;
        try {
            Map<String, Bitmap> cache = snapshot();
            if (failure.get() == null && (images.loading || !cache.keySet().containsAll(images.wanted))) return;
            elapsedNs = System.nanoTime() - startNs;
            idleCache = cache;
        } catch (AssertionError error) { failure.compareAndSet(null, error); }
        settled.countDown();
    }

    private Map<String, Bitmap> snapshot() {
        long pixels = 0, bytes = 0;
        // Copy by iteration so test inspection never touches the access-order cache.
        Map<String, Bitmap> cache = new LinkedHashMap<>(images.bitmaps);
        for (Map.Entry<String, Bitmap> entry : cache.entrySet()) {
            Bitmap bitmap = entry.getValue();
            if (bitmap == null) { check(BAD.equals(entry.getKey()) && images.wanted.contains(BAD), "Only a visible failed image may retain null"); continue; }
            check(!bitmap.isRecycled(), "Cached bitmap remains live: " + entry.getKey());
            pixels += (long) bitmap.getWidth() * bitmap.getHeight();
            bytes += bitmap.getAllocationByteCount();
        }
        check(pixels <= MAX_PIXELS, "All cached pixels stay within 8M: " + pixels);
        check(cache.size() <= Math.max(32, images.wanted.size()), "Only visible images may exceed the 32-entry limit");
        peakPixels = Math.max(peakPixels, pixels); peakBytes = Math.max(peakBytes, bytes);
        return cache;
    }

    private void clear() throws Exception {
        instrumentation.runOnMainChecked(() -> {
            images.suspend();
            check(snapshot().isEmpty() && images.wanted.isEmpty() && !images.loading, "Idle suspend clears cache, wanted and loading");
            images.resume();
            idleCache = null;
        });
    }

    private static boolean full(Bitmap bitmap, int size) { return bitmap != null && bitmap.getWidth() == size && bitmap.getHeight() == size; }
    private static String logo(int index) { return "word/media/logo" + index + ".png"; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static File fixture(File directory) throws IOException {
        File file = File.createTempFile("image-cache-", ".docx", directory);
        boolean complete = false;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            text(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rDoc\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            text(zip, "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>");
            String[] large = {A, B, C};
            int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
            for (int i = 0; i < LOGOS + large.length; i++) {
                Bitmap bitmap = Bitmap.createBitmap(i < large.length ? BIG : LOGO, i < large.length ? BIG : LOGO, Bitmap.Config.ARGB_8888);
                try {
                    bitmap.eraseColor(i < large.length ? colors[i] : Color.CYAN);
                    zip.putNextEntry(new ZipEntry(i < large.length ? large[i] : logo(i - large.length)));
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip), "Encode cache fixture image");
                    zip.closeEntry();
                } finally { bitmap.recycle(); }
            }
            text(zip, BAD, "Not a PNG");
            complete = true;
        } finally { if (!complete) file.delete(); }
        return file;
    }

    private static void text(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
