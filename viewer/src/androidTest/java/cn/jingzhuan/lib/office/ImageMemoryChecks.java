package cn.jingzhuan.lib.office;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ImageMemoryChecks {
    private static final int PAGE_COUNT = 16, IMAGE_SIZE = 2000, VIEW_SIZE = 600;
    private static final int NAVIGATION_ROUNDS = 3;
    private static final long IMAGE_PADDING = 6L * 1024 * 1024;
    private static final long MAX_PIXELS = 8_000_000L, MAX_EXPANDED = OfficePackage.MAX_EXPANDED;
    private static final long TIMEOUT_MS = 20_000;
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PRESENTATION_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private final Instrumentation instrumentation;
    private final PreviewTestActivity activity;
    private final OfficePreviewView view;
    private final File cacheDir;
    private Bitmap frame;
    private Canvas canvas;
    private long peakPixels;
    private String lastState = "No draw yet";

    private ImageMemoryChecks(Instrumentation instrumentation, PreviewTestActivity activity) {
        this.instrumentation = instrumentation;
        this.activity = activity;
        view = activity.preview;
        cacheDir = activity.getCacheDir();
    }

    static String run(Instrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "Run image checks on the instrumentation thread");
        return new ImageMemoryChecks(instrumentation, activity).run();
    }

    private String run() throws Exception {
        File deck = null, replacement = null;
        int[] layout = new int[8];
        float[] zoom = new float[1];
        Set<String> previousPackages = packageFiles();
        onMain(() -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            layout[0] = params.width; layout[1] = params.height;
            layout[2] = view.getLeft(); layout[3] = view.getTop();
            layout[4] = view.getRight(); layout[5] = view.getBottom();
            layout[6] = view.getMeasuredWidth(); layout[7] = view.getMeasuredHeight();
            zoom[0] = view.getZoom();
        });
        try {
            deck = fixture(cacheDir, PAGE_COUNT, false);
            replacement = fixture(cacheDir, 2, true);
            long[] imageBytes = verifyFixture(deck);
            frame = Bitmap.createBitmap(VIEW_SIZE, VIEW_SIZE, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(frame);
            onMain(() -> {
                view.clear();
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = params.height = VIEW_SIZE;
                view.setLayoutParams(params);
                view.measure(exact(VIEW_SIZE), exact(VIEW_SIZE));
                view.layout(0, 0, VIEW_SIZE, VIEW_SIZE);
            });

            Uri originalUri = Uri.fromFile(deck), replacementUri = Uri.fromFile(replacement);
            Load original = new Load();
            onMain(() -> view.open(originalUri, original));
            original.awaitLoaded(PAGE_COUNT, "16 distinct 2000x2000 images must reach onLoaded");
            Bitmap first = awaitPage(1, false);
            int contrast = clarity();
            check(first.getWidth() == IMAGE_SIZE && first.getHeight() == IMAGE_SIZE,
                "High-resolution PPTX image retains source pixels");
            check(contrast > 200, "Zoomed one-pixel image detail retains contrast: " + contrast);
            first = awaitPage(1, false);
            Object attachedImages = images();
            onMain(() -> activity.setContentView(new View(activity)));
            Snapshot detached = snapshot(attachedImages);
            check(detached.bitmaps.isEmpty() && detached.wanted.isEmpty() && !detached.loading,
                "Detaching suspends and releases the image cache: cached=" + detached.bitmaps.keySet()
                    + ", wanted=" + detached.wanted + ", loading=" + detached.loading);
            onMain(() -> {
                activity.setContentView(view, new ViewGroup.LayoutParams(VIEW_SIZE, VIEW_SIZE));
                view.measure(exact(VIEW_SIZE), exact(VIEW_SIZE));
                view.layout(0, 0, VIEW_SIZE, VIEW_SIZE);
            });
            check(attachedImages == images(), "Reattaching retains the document image source");
            check(first != awaitPage(1, false), "Reattached view reloads its visible image");
            first = null;
            jump(PAGE_COUNT);
            awaitPage(PAGE_COUNT, false);
            jump(1);
            awaitPage(1, false);

            onMain(() -> {
                // Portrait slides are 360 x 720 points with a 16-point inter-page gap.
                float scale = Math.max(1, VIEW_SIZE - 24 * view.getResources().getDisplayMetrics().density) / 360f;
                dragBy(736 * scale);
                check(view.getCurrentPage() == 2, "Dragging selects the second page");
            });
            awaitPage(2, false);

            long logicalReads = 0;
            for (int round = 0; round < NAVIGATION_ROUNDS; round++) {
                for (int page = 1; page <= PAGE_COUNT; page++) {
                    jump(page);
                    awaitPage(page, false);
                    logicalReads += imageBytes[page - 1];
                }
            }
            check(logicalReads > MAX_EXPANDED, "Revisits exceed 256 MiB of logical image reads");

            Load stale = new Load(), current = new Load();
            onMain(() -> {
                view.jumpToPage(1);
                draw();
                check(snapshot(images()).loading, "Replacement interrupts an outstanding image request");
                view.open(originalUri, stale);
                view.open(replacementUri, current);
            });
            current.awaitLoaded(2, "Rapid URI replacement loads the latest document");
            awaitPage(1, true);
            check(stale.callbacks.get() == 0, "Replaced document emits no stale callbacks");
            check(original.callbacks.get() == 1, "Original document reports one successful load");

            AtomicReference<Object> clearedImages = new AtomicReference<>();
            onMain(() -> {
                view.jumpToPage(2);
                draw();
                clearedImages.set(images());
                check(snapshot(clearedImages.get()).loading, "Clear interrupts an outstanding image request");
                view.clear();
            });
            awaitClear(clearedImages.get(), previousPackages);
            Load cancelled = new Load();
            onMain(() -> { view.open(originalUri, cancelled); view.clear(); });
            awaitClear(null, previousPackages);
            check(cancelled.callbacks.get() == 0, "Cleared document emits no late callback");
            check(original.callbacks.get() == 1 && stale.callbacks.get() == 0 && current.callbacks.get() == 1,
                "Replacement and image cancellation do not deliver late document callbacks");
            return "IMAGE MEMORY CHECKS\n"
                + "PASS 2000x2000 source pixels and 4x raster detail contrast=" + contrast + "\n"
                + "SCREENSHOTS image-clarity-fit.png, image-clarity-zoom.png\n"
                + "PASS 16-image document delivers UI onLoaded; visible images render and offscreen entries are evicted\n"
                + "PASS jump, drag, return and " + NAVIGATION_ROUNDS + " full passes; logical image reads=" + logicalReads
                + ", peak cached pixels=" + peakPixels + "\n"
                + "PASS rapid URI replacement, same-path image isolation, pending-load clear and temporary-file cleanup\n"
                + "IMAGE MEMORY CHECKS PASSED\n";
        } finally {
            try {
                onMain(() -> {
                    view.clear();
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.width = layout[0]; params.height = layout[1];
                    view.setLayoutParams(params);
                    view.measure(exact(layout[6]), exact(layout[7]));
                    view.layout(layout[2], layout[3], layout[4], layout[5]);
                    view.setZoom(zoom[0]);
                    view.requestLayout();
                    if (frame != null) frame.recycle();
                });
            } finally {
                if (deck != null) deck.delete();
                if (replacement != null) replacement.delete();
            }
        }
    }

    private void jump(int page) throws Exception {
        onMain(() -> view.jumpToPage(page));
    }

    private int clarity() throws Exception {
        AtomicInteger contrast = new AtomicInteger();
        onMain(() -> {
            saveFrame("image-clarity-fit");
            view.setZoom(4); view.jumpToPage(1); draw();
            saveFrame("image-clarity-zoom");
            float density = view.getResources().getDisplayMetrics().density;
            float imageWidth = (VIEW_SIZE - 24 * density) * 4;
            int y = Math.round(12 * density + 100f / IMAGE_SIZE * imageWidth * 1.5f);
            int min = 255, max = 0;
            for (int x = (int) Math.ceil(64f / IMAGE_SIZE * imageWidth);
                    x < 240f / IMAGE_SIZE * imageWidth; x++) {
                int value = Color.red(frame.getPixel(x, y));
                min = Math.min(min, value); max = Math.max(max, value);
            }
            contrast.set(max - min);
            view.resetZoom(); view.jumpToPage(1); draw();
        });
        return contrast.get();
    }

    private void saveFrame(String name) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), name + ".png"))) {
            check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Save " + name);
        }
    }

    private Bitmap awaitPage(int page, boolean alternate) throws Exception {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        Set<String> visible = Collections.singleton(imagePath(page));
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            onMain(() -> {
                draw();
                Snapshot state = snapshot(images());
                lastState = "page=" + view.getCurrentPage() + ", wanted=" + state.wanted
                    + ", cached=" + state.bitmaps.keySet() + ", loading=" + state.loading;
                if (state.loading || !state.wanted.equals(visible) || !state.bitmaps.keySet().equals(visible)) return;
                check(view.getCurrentPage() == page, "Rendered page agrees with navigation: " + page);
                int expected = color(page, alternate);
                for (int y : new int[] {240, 300, 360}) for (int x : new int[] {240, 300, 360}) {
                    int actual = frame.getPixel(x, y);
                    check(closeColor(actual, expected), "Page " + page + " pixel " + x + "," + y
                        + " expected #" + Integer.toHexString(expected) + " actual #" + Integer.toHexString(actual));
                }
                result.set(state.bitmaps.get(imagePath(page)));
            });
            if (result.get() != null) return result.get();
            Thread.sleep(20);
        }
        throw new AssertionError("Image rendering/eviction timed out for page " + page + ": " + lastState);
    }

    private void awaitClear(Object oldImages, Set<String> previousPackages) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS, stableSince = 0;
        AtomicBoolean idle = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            onMain(() -> {
                draw();
                check(view.getPageCount() == 0 && view.getCurrentPage() == 0, "Clear resets page state");
                Snapshot current = snapshot(images());
                Snapshot old = snapshot(oldImages);
                assertEmpty(current);
                assertEmpty(old);
                idle.set(!current.loading && !old.loading);
            });
            Set<String> remaining = packageFiles();
            remaining.removeAll(previousPackages);
            if (remaining.isEmpty() && idle.get()) {
                if (stableSince == 0) stableSince = SystemClock.uptimeMillis();
                if (SystemClock.uptimeMillis() - stableSince >= 500) return;
            } else stableSince = 0;
            lastState = "Temporary packages remaining: " + remaining + ", image worker idle=" + idle.get();
            Thread.sleep(20);
        }
        throw new AssertionError("Clear did not release temporary packages: " + lastState);
    }

    private static void assertEmpty(Snapshot state) {
        check(state.bitmaps.isEmpty() && state.wanted.isEmpty(), "Clear removes cached bitmaps and requested images");
    }

    private void draw() {
        check(view.getWidth() == VIEW_SIZE && view.getHeight() == VIEW_SIZE, "Stable 600x600 test viewport");
        frame.eraseColor(Color.TRANSPARENT);
        view.draw(canvas);
    }

    private void dragBy(float distance) {
        while (distance > 0.01f) {
            float step = Math.min(400, distance);
            long down = SystemClock.uptimeMillis();
            touch(down, down, MotionEvent.ACTION_DOWN, 500);
            for (int i = 1; i <= 4; i++) touch(down, down + i * 20, MotionEvent.ACTION_MOVE, 500 - step * i / 4);
            touch(down, down + 100, MotionEvent.ACTION_CANCEL, 500 - step);
            distance -= step;
        }
    }

    private void touch(long down, long time, int action, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, VIEW_SIZE / 2f, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private Object images() throws Exception {
        return field(OfficePreviewView.class, "images").get(view);
    }

    @SuppressWarnings("unchecked")
    private Snapshot snapshot(Object images) throws Exception {
        if (images == null) return new Snapshot(Collections.emptyMap(), Collections.emptySet(), false);
        Map<String, Bitmap> bitmaps = (Map<String, Bitmap>) field(images.getClass(), "bitmaps").get(images);
        Set<String> wanted = (Set<String>) field(images.getClass(), "wanted").get(images);
        boolean loading = field(images.getClass(), "loading").getBoolean(images);
        long pixels = 0;
        for (Map.Entry<String, Bitmap> entry : bitmaps.entrySet()) {
            Bitmap bitmap = entry.getValue();
            check(bitmap != null && !bitmap.isRecycled(), "Cache contains a live bitmap: " + entry.getKey());
            pixels += (long) bitmap.getWidth() * bitmap.getHeight();
        }
        peakPixels = Math.max(peakPixels, pixels);
        check(pixels <= MAX_PIXELS, "Current bitmap pixels exceed the 8M budget: " + pixels);
        return new Snapshot(bitmaps, wanted, loading);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Snapshot {
        final Map<String, Bitmap> bitmaps;
        final Set<String> wanted;
        final boolean loading;
        Snapshot(Map<String, Bitmap> bitmaps, Set<String> wanted, boolean loading) {
            this.bitmaps = bitmaps; this.wanted = wanted; this.loading = loading;
        }
    }

    private Set<String> packageFiles() throws IOException {
        File directory = new File(cacheDir, "office-preview");
        if (!directory.exists()) return new HashSet<>();
        String[] names = directory.list();
        if (names == null) throw new IOException("Cannot list test cache directory");
        return new HashSet<>(Arrays.asList(names));
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private void onMain(CheckedRunnable operation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { operation.run(); } catch (Exception | AssertionError error) { failure.set(error); }
        });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
    }

    private static final class Load implements OfficePreviewView.Listener {
        final AtomicInteger callbacks = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        volatile OfficePreviewView.Info info;
        volatile Exception error;
        volatile boolean onMain;
        @Override public void onLoaded(OfficePreviewView.Info info) { this.info = info; complete(); }
        @Override public void onError(Exception error) { this.error = error; complete(); }
        private void complete() {
            onMain = Looper.myLooper() == Looper.getMainLooper();
            callbacks.incrementAndGet(); done.countDown();
        }
        void awaitLoaded(int pages, String label) throws Exception {
            check(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), label + ": timed out");
            if (error != null) throw new AssertionError(label + ": " + error, error);
            check(onMain && callbacks.get() == 1 && info != null, label + ": exactly one UI onLoaded callback");
            check("PPTX".equals(info.format) && info.pageCount == pages, label + ": PPTX page count");
        }
    }

    private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }

    private static boolean closeColor(int actual, int expected) {
        return Color.alpha(actual) == 255 && Math.abs(Color.red(actual) - Color.red(expected)) <= 2
            && Math.abs(Color.green(actual) - Color.green(expected)) <= 2 && Math.abs(Color.blue(actual) - Color.blue(expected)) <= 2;
    }

    private static int color(int page, boolean alternate) {
        int value = Color.rgb(32 + page * 43 % 192, 32 + page * 67 % 192, 32 + page * 97 % 192);
        return alternate ? value ^ 0x00ffffff : value;
    }

    private static String imagePath(int page) { return "ppt/media/image" + page + ".png"; }

    private static File fixture(File directory, int pages, boolean alternate) throws IOException {
        File file = File.createTempFile("image-memory-", ".pptx", directory);
        boolean complete = false;
        Bitmap bitmap = null;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            StringBuilder types = new StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>"
                + override("ppt/presentation.xml", "presentationml.presentation.main"));
            StringBuilder presentation = new StringBuilder(XML + "<p:presentation xmlns:p=\"" + PRESENTATION_NS
                + "\" xmlns:a=\"" + DRAWING_NS + "\" xmlns:r=\"" + REL_NS + "\"><p:sldIdLst>");
            StringBuilder relations = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                types.append(override("ppt/slides/slide" + page + ".xml", "presentationml.slide"));
                presentation.append("<p:sldId id=\"").append(255 + page).append("\" r:id=\"rSlide").append(page).append("\"/>");
                relations.append(relation("rSlide" + page, "slide", "slides/slide" + page + ".xml"));
            }
            xml(zip, "[Content_Types].xml", types.append("</Types>").toString());
            xml(zip, "_rels/.rels", relationships(relation("rOffice", "officeDocument", "ppt/presentation.xml")));
            xml(zip, "ppt/presentation.xml", presentation.append("</p:sldIdLst><p:sldSz cx=\"4572000\" cy=\"9144000\"/>"
                + "<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>").toString());
            xml(zip, "ppt/_rels/presentation.xml.rels", relationships(relations.toString()));
            bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
            byte[] padding = new byte[8192];
            zip.setLevel(0);
            for (int page = 1; page <= pages; page++) {
                xml(zip, "ppt/slides/slide" + page + ".xml", slide());
                xml(zip, "ppt/slides/_rels/slide" + page + ".xml.rels",
                    relationships(relation("rImage", "image", "../media/image" + page + ".png")));
                bitmap.eraseColor(color(page, alternate));
                if (page == 1) {
                    Canvas detail = new Canvas(bitmap);
                    Paint stripe = new Paint();
                    for (int x = 32; x < 256; x++) {
                        stripe.setColor(x % 2 == 0 ? Color.BLACK : Color.WHITE);
                        detail.drawRect(x, 32, x + 1, 256, stripe);
                    }
                }
                zip.putNextEntry(new ZipEntry(imagePath(page)));
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) throw new IOException("Cannot encode fixture image");
                // Trailing PNG padding exercises package reads without increasing decoded pixels.
                for (long bytes = 0; bytes < IMAGE_PADDING; bytes += padding.length) zip.write(padding);
                zip.closeEntry();
            }
            complete = true;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (!complete) file.delete();
        }
        return file;
    }

    private static long[] verifyFixture(File file) throws IOException {
        long[] imageBytes = new long[PAGE_COUNT];
        check(file.length() > 64L * 1024 * 1024 && file.length() <= OfficePackage.MAX_INPUT,
            "Large PPTX fixture exceeds the old 64 MiB input limit");
        try (ZipFile zip = new ZipFile(file)) {
            long expanded = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) expanded += entries.nextElement().getSize();
            check(expanded <= MAX_EXPANDED, "Unique expanded fixture data fits within 256 MiB");
            check((long) PAGE_COUNT * IMAGE_SIZE * IMAGE_SIZE > MAX_PIXELS, "Fixture exceeds the aggregate pixel budget");
            for (int page = 1; page <= PAGE_COUNT; page++) {
                imageBytes[page - 1] = zip.getEntry(imagePath(page)).getSize();
                check(imageBytes[page - 1] > IMAGE_PADDING, "Each PNG has six MiB of trailing padding");
            }
        }
        return imageBytes;
    }

    private static String slide() {
        return XML + "<p:sld xmlns:p=\"" + PRESENTATION_NS + "\" xmlns:a=\"" + DRAWING_NS + "\" xmlns:r=\"" + REL_NS + "\">"
            + "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></p:bgPr></p:bg><p:spTree>"
            + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
            + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
            + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
            + "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page image\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
            + "<p:blipFill><a:blip r:embed=\"rImage\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
            + "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"4572000\" cy=\"6858000\"/></a:xfrm>"
            + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
            + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }

    private static String override(String part, String type) {
        return "<Override PartName=\"/" + part + "\" ContentType=\"application/vnd.openxmlformats-officedocument." + type + "+xml\"/>";
    }

    private static String relation(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + REL_NS + "/" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String relationships(String body) {
        return XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + body + "</Relationships>";
    }

    private static void xml(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
