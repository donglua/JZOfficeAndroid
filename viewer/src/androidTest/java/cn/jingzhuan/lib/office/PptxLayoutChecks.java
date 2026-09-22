package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PptxLayoutChecks {
    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        instrumentation.runOnMainChecked(PptxLayoutChecks::rendererChecks);
        Uri sample = Uri.parse("content://" + instrumentation.getTargetContext().getPackageName()
            + ".fixtures/samples/sample.pptx");
        StringBuilder result = new StringBuilder("PASS PPTX on-demand metadata, visibility/order, reuse, eviction/pixels, empty/gap/end, warnings and DOCX isolation\n");
        try (OfficePackage source = OfficePackage.open(instrumentation.getTargetContext(), sample)) {
            check(source.document.pages.size() == 16, "Benchmark uses the real 16-slide sample");
            instrumentation.runOnMainChecked(() -> result.append(benchmark(source.document)));
        }
        previewChecks(instrumentation, activity, sample);
        return result + "PASS sample URI/view: zero-viewport onLoaded jump to slide 10, one-line chapter labels, eviction, return/zoom and detach/reattach\n";
    }

    private static void rendererChecks() throws Exception {
        OfficeDocument document = fixture();
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        List<OfficeRenderer.Page> metadata = new ArrayList<>(renderer.pages);
        geometry(renderer, document, metadata);
        ready(renderer, 0, 1);
        Constructor<OfficePreviewView.Info> constructor = OfficePreviewView.Info.class.getDeclaredConstructor(OfficeDocument.class);
        constructor.setAccessible(true);
        OfficePreviewView.Info info = constructor.newInstance(document);
        check(info.pageCount == 12 && info.warnings.equals(Collections.singletonList("Fixture parse warning")),
            "Initial metadata and warnings do not require later slide layout");

        OfficeRenderer.Page target = renderer.pages.get(9);
        List<String> expected = Arrays.asList("slide-9-front", "slide-9-back");
        check(new ArrayList<>(renderer.visibleImages(0, target.y, 160, target.y + target.height, 9)).equals(expected),
            "Arbitrary image discovery prepares the slide and requests only visible images, frontmost first");
        ready(renderer, 8, 10);
        check(document.warnings.equals(Arrays.asList("Fixture parse warning", "Text exceeds its slide box and is clipped")),
            "Preparing a later overflowing slide adds its layout warning internally");
        List<OfficeRenderer.Element> elements = new ArrayList<>(target.elements);
        Object textLayout = elements.get(0).texts.get(0).layout;
        for (int i = 0; i < elements.size(); i++) {
            check(elements.get(i).source == document.pages.get(9).elements.get(i), "Slide element order remains unchanged");
        }
        Bitmap back = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Bitmap front = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        back.eraseColor(Color.BLUE); front.eraseColor(Color.RED);
        Map<String, Bitmap> images = new HashMap<>();
        images.put("slide-9-back", back); images.put("slide-9-front", front);
        Bitmap before = null, after = null;
        try {
            before = pageBitmap(renderer, 9, images);
            check(before.getPixel(185, 45) == document.pages.get(9).elements.get(1).fill
                    && before.getPixel(34, 92) == Color.BLUE && before.getPixel(50, 100) == Color.RED,
                "Prepared slide paints its colored shape and ordered image layers");
            int ink = 0;
            for (int y = 12; y < 40; y++) for (int x = 12; x < 150; x++) {
                if (Color.red(before.getPixel(x, y)) < 100) ink++;
            }
            check(ink > 20, "Prepared text paints actual glyphs");
            for (int i = 0; i < 3; i++) {
                renderer.visibleImages(0, target.y, 160, target.y + target.height, 9);
                renderer.draw(new Canvas(), target.y, target.y + target.height, images);
                check(target.elements.equals(elements) && target.elements.get(0).texts.get(0).layout == textLayout,
                    "Repeated discovery/draw reuses element and text layout identities");
            }
            OfficeRenderer.Page previous = renderer.pages.get(8), next = renderer.pages.get(10);
            check(new ArrayList<>(renderer.visibleImages(0, previous.y, 160, next.y + next.height, 9)).equals(Arrays.asList(
                "slide-9-front", "slide-9-back", "slide-8-front", "slide-8-back", "slide-10-front", "slide-10-back")),
                "Current-slide priority, page order and frontmost image priority survive lazy preparation");
            ready(renderer, 7, 11);
            renderer.preparePages(renderer.pages.get(3).y, renderer.pages.get(6).y + renderer.pages.get(6).height);
            ready(renderer, 2, 7);
            check(target.elements.isEmpty(), "Four-page viewport plus neighbors evicts the distant slide");
            OfficeRenderer.Page empty = renderer.pages.get(5);
            renderer.visibleImages(0, empty.y, renderer.width, empty.y + empty.height, 5);
            ready(renderer, 4, 6);
            OfficeRenderer.Element neighbor = renderer.pages.get(4).elements.get(0);
            check(empty.ready && empty.elements.isEmpty(), "Empty slides are marked prepared");
            renderer.draw(new Canvas(), empty.y, empty.y + empty.height, Collections.emptyMap());
            check(renderer.visibleImages(0, empty.y, renderer.width, empty.y + empty.height, 5).isEmpty()
                    && empty.ready && renderer.pages.get(4).elements.get(0) == neighbor,
                "Empty slide redraw/discovery retains readiness and neighbor layouts");
            float end = empty.y + empty.height, gap = end + 8;
            check(renderer.visibleImages(0, gap, renderer.width, gap + 1, 5).isEmpty(), "Page gap requests no images");
            ready(renderer, 5, 6);
            renderer.preparePages(end, end);
            ready(renderer, 4, 6);
            renderer.preparePages(renderer.pages.get(6).y, renderer.pages.get(6).y);
            ready(renderer, 5, 7);
            renderer.preparePages(-100, 0);
            ready(renderer, 0, 1);
            check(renderer.visibleImages(0, renderer.height + 1, renderer.width, renderer.height + 100, 11).isEmpty(),
                "Beyond-document viewport requests no images");
            ready(renderer, 10, 11);
            after = pageBitmap(renderer, 9, images);
            ready(renderer, 8, 10);
            check(before.sameAs(after), "Eviction and direct draw revisit preserve every pixel");
            check(target.elements.get(0) != elements.get(0) && target.elements.get(0).texts.get(0).layout != textLayout,
                "Evicted elements and text layouts are rebuilt on draw");
            geometry(renderer, document, metadata);
            check(document.warnings.size() == 2 && info.warnings.equals(Collections.singletonList("Fixture parse warning")),
                "Revisit deduplicates warnings and leaves the loaded snapshot unchanged");
            try { info.warnings.add("mutation"); throw new AssertionError("Loaded warnings are mutable"); }
            catch (UnsupportedOperationException expectedFailure) { }
        } finally {
            if (before != null) before.recycle();
            if (after != null) after.recycle();
            back.recycle(); front.recycle();
        }

        OfficeDocument replacement = fixture();
        replacement.pages.subList(2, replacement.pages.size()).clear();
        renderer.layout(replacement);
        ready(renderer, 0, 1);
        check(renderer.pages.size() == 2 && renderer.pages.get(0) != metadata.get(0)
                && renderer.pages.get(0).elements.get(0).source == replacement.pages.get(0).elements.get(0)
                && field(renderer, "document") == replacement, "Replacement resets geometry, layouts and retained source");
        OfficeDocument docx = new OfficeDocument(); docx.kind = OfficeDocument.Kind.DOCX;
        for (int i = 0; i < 6; i++) docx.blocks.add(text("DOCX block " + i));
        renderer.layout(docx);
        OfficeRenderer.Page flow = renderer.pages.get(0);
        check(renderer.pages.size() == 1 && flow.ready && flow.elements.size() == 6, "DOCX eagerly lays out every block");
        List<OfficeRenderer.Element> flowElements = new ArrayList<>(flow.elements);
        for (int i = 0; i < flow.elements.size(); i++) {
            check(!flow.elements.get(i).texts.isEmpty() && (i == 0 || flow.elements.get(i).y > flow.elements.get(i - 1).y),
                "DOCX retains eager text layout and continuous flow geometry");
        }
        renderer.visibleImages(0, 0, renderer.width, 1, 0);
        renderer.draw(new Canvas(), renderer.height + 1, renderer.height + 100, Collections.emptyMap());
        check(flow.elements.equals(flowElements), "PPTX preparation/eviction does not affect DOCX blocks");
        renderer.clear();
        renderer.preparePages(0, 1000);
        renderer.draw(new Canvas(), 0, 1000, Collections.emptyMap());
        check(renderer.pages.isEmpty() && renderer.width == 0 && renderer.height == 0 && field(renderer, "document") == null
                && renderer.visibleImages(0, 0, 100, 100, 0).isEmpty(), "Clear releases the retained source and cannot resurrect pages");
        OfficeDocument emptyDeck = new OfficeDocument(); emptyDeck.kind = OfficeDocument.Kind.PPTX;
        renderer.layout(emptyDeck); renderer.preparePages(0, 100);
        check(renderer.pages.isEmpty() && renderer.height == 0, "Empty deck has no prepared pages or trailing gap");
        renderer.clear();
    }

    private static void previewChecks(OfficeInstrumentation instrumentation, PreviewTestActivity activity, Uri sample) throws Exception {
        OfficePreviewView view = activity.preview;
        AtomicReference<ViewGroup.LayoutParams> original = new AtomicReference<>();
        int[] size = new int[4];
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<OfficePreviewView.Info> info = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Bitmap frame = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888);
        try {
            instrumentation.runOnMainChecked(() -> {
                original.set(view.getLayoutParams()); size[0] = view.getWidth(); size[1] = view.getHeight();
                size[2] = original.get().width; size[3] = original.get().height;
                view.clear();
                original.get().width = original.get().height = 0;
                view.setLayoutParams(original.get()); measure(view, 0, 0);
                view.open(sample, new OfficePreviewView.Listener() {
                    @Override public void onLoaded(OfficePreviewView.Info result) {
                        try {
                            info.set(result);
                            check(result.pageCount == 16 && view.getPageCount() == 16, "onLoaded publishes all 16 slide geometries");
                            check(view.getWidth() == 0 && view.getHeight() == 0, "onLoaded runs at a zero-size viewport");
                            OfficeRenderer renderer = (OfficeRenderer) field(view, "renderer");
                            ready(renderer, 0, 1);
                            view.jumpToPage(10);
                            check(view.getCurrentPage() == 10 && !renderer.pages.get(9).ready,
                                "onLoaded jump records a distant page before viewport layout");
                        } catch (Exception | AssertionError error) { failure.set(error); }
                        finally { loaded.countDown(); }
                    }
                    @Override public void onError(Exception error) { failure.set(error); loaded.countDown(); }
                });
            });
            check(loaded.await(20, TimeUnit.SECONDS), "Real sample finishes loading");
            if (failure.get() != null) throw new AssertionError("Real sample callback failed", failure.get());
            instrumentation.runOnMainChecked(() -> {
                OfficeRenderer renderer = (OfficeRenderer) field(view, "renderer");
                List<String> warnings = new ArrayList<>(info.get().warnings);
                original.get().width = 1080; original.get().height = 600;
                view.setLayoutParams(original.get()); measure(view, 1080, 600);
                Canvas canvas = new Canvas(frame); view.draw(canvas);
                check(view.getCurrentPage() == 10 && view.getPageCount() == 16, "First nonzero measurement applies the pending page-10 jump");
                Object chapterLayout = chapter(renderer);
                check(!renderer.pages.get(0).ready && renderer.pages.get(0).elements.isEmpty(), "Distant view draw evicts first-slide layouts");
                int changed = 0, background = frame.getPixel(100, 100);
                for (int y = 0; y < frame.getHeight(); y += 8) for (int x = 0; x < frame.getWidth(); x += 8) {
                    if (frame.getPixel(x, y) != background) changed++;
                }
                check(changed > 20, "Real sample view draws nonblank pixels");
                view.draw(canvas);
                check(chapter(renderer) == chapterLayout, "Repeated view draw reuses chapter text layout");
                view.setZoom(2); view.jumpToPage(10); view.draw(canvas);
                check(view.getZoom() == 2 && view.getCurrentPage() == 10 && chapter(renderer) == chapterLayout,
                    "Zoom reuses slide layouts while retaining navigation");
                activity.setContentView(new View(activity));
                check(!view.isAttachedToWindow(), "Preview actually detaches");
                activity.setContentView(view, new ViewGroup.LayoutParams(1080, 600)); measure(view, 1080, 600);
                view.draw(canvas);
                check(view.isAttachedToWindow() && view.getPageCount() == 16 && view.getCurrentPage() == 10
                        && view.getZoom() == 2 && chapter(renderer) == chapterLayout,
                    "Reattachment preserves document, page, zoom and prepared text");
                view.resetZoom(); view.jumpToPage(1); view.draw(canvas);
                check(view.getCurrentPage() == 1 && renderer.pages.get(0).ready && !renderer.pages.get(9).ready,
                    "Returning to the first page evicts the chapter slide");
                view.jumpToPage(10); view.draw(canvas);
                check(view.getCurrentPage() == 10 && chapter(renderer) != chapterLayout,
                    "Returning to the chapter rebuilds its one-line labels");
                check(info.get().warnings.equals(warnings), "onLoaded warning snapshot survives later view layouts");
                try { info.get().warnings.add("mutation"); throw new AssertionError("onLoaded warnings are mutable"); }
                catch (UnsupportedOperationException expected) { }
                view.clear();
                check(view.getPageCount() == 0 && view.getCurrentPage() == 0 && field(renderer, "document") == null,
                    "View clear releases the renderer document");
            });
        } finally {
            frame.recycle();
            instrumentation.runOnMainChecked(() -> {
                view.clear();
                if (original.get() != null) {
                    original.get().width = size[2]; original.get().height = size[3];
                    activity.setContentView(view, original.get()); measure(view, size[0], size[1]); view.requestLayout();
                }
            });
        }
    }

    private static Object chapter(OfficeRenderer renderer) {
        int labels = 0;
        Object title = null;
        for (OfficeRenderer.Element element : renderer.pages.get(9).elements) {
            if (element.texts.isEmpty()) continue;
            OfficeTextLayout.Block block = element.texts.get(0);
            String text = block.layout.getText().toString();
            if ("PART 01".equals(text) || "\u7ae0\u8282\u6807\u9898\uff0c\u5b8c\u6574\u663e\u793a".equals(text)) {
                check(block.layout.getLineCount() == 1, "Page-10 chapter label stays on one line: " + text);
                labels++; title = block.layout;
            }
        }
        check(labels == 2, "View prepares both real page-10 chapter labels");
        return title;
    }

    private static String benchmark(OfficeDocument document) {
        int warmups = 3, samples = 9;
        long[][] timings = new long[2][samples];
        for (int i = -warmups; i < samples; i++) for (int pass = 0; pass < 2; pass++) {
            int mode = (i + warmups + pass) % 2;
            OfficeRenderer renderer = new OfficeRenderer();
            long start = System.nanoTime();
            renderer.layout(document);
            if (mode == 1) renderer.preparePages(0, renderer.height);
            long elapsed = System.nanoTime() - start;
            if (i >= 0) timings[mode][i] = elapsed;
            ready(renderer, 0, mode == 0 ? 1 : document.pages.size() - 1);
            renderer.clear();
        }
        Arrays.sort(timings[0]); Arrays.sort(timings[1]);
        return String.format(Locale.ROOT,
            "PPTX layout-phase only, real 16-slide sample: %d warmups + %d samples/path, alternating order; "
                + "median layout=%.3f ms, layout+preparePages(all)=%.3f ms; excludes URI I/O, parsing, image decode and drawing; no speed threshold\n",
            warmups, samples, timings[0][samples / 2] / 1_000_000.0, timings[1][samples / 2] / 1_000_000.0);
    }

    private static OfficeDocument fixture() {
        OfficeDocument document = new OfficeDocument(); document.kind = OfficeDocument.Kind.PPTX; document.width = 320;
        document.warn("Fixture parse warning");
        for (int i = 0; i < 12; i++) {
            OfficeDocument.Page page = new OfficeDocument.Page();
            page.width = 320 - i; page.height = 180 + i % 3 * 10;
            page.background = Color.rgb(235 + i, 245, 250 - i); document.pages.add(page);
            if (i == 5) continue;
            OfficeDocument.Element title = text("Slide " + (i + 1));
            if (i == 8) title.height = 1;
            page.elements.add(title);
            OfficeDocument.Element shape = new OfficeDocument.Element(); shape.type = OfficeDocument.Type.RECT;
            shape.width = 60; shape.height = 40; shape.transform[4] = 180; shape.transform[5] = 40;
            shape.fill = Color.rgb(30 + i * 10, 90, 140); page.elements.add(shape);
            for (int image = 0; image < 3; image++) {
                OfficeDocument.Element picture = new OfficeDocument.Element(); picture.type = OfficeDocument.Type.IMAGE;
                picture.width = 40; picture.height = 30;
                picture.transform[4] = image == 2 ? 260 : 32 + image * 12;
                picture.transform[5] = 90 + image * 4;
                picture.image = "slide-" + i + (image == 0 ? "-back" : image == 1 ? "-front" : "-outside");
                page.elements.add(picture);
            }
        }
        return document;
    }

    private static OfficeDocument.Element text(String value) {
        OfficeDocument.Element element = new OfficeDocument.Element(); element.width = 200; element.height = 50; element.padding = 0;
        element.transform[4] = element.transform[5] = 12;
        OfficeDocument.Paragraph paragraph = new OfficeDocument.Paragraph(); paragraph.after = 0;
        OfficeDocument.Run run = new OfficeDocument.Run(); run.text = value; run.size = 18;
        paragraph.runs.add(run); element.paragraphs.add(paragraph);
        return element;
    }

    private static void geometry(OfficeRenderer renderer, OfficeDocument document, List<OfficeRenderer.Page> metadata) {
        check(renderer.pages.size() == document.pages.size() && renderer.width == document.width, "All slide metadata is available");
        float y = 0;
        for (int i = 0; i < document.pages.size(); i++) {
            OfficeRenderer.Page actual = renderer.pages.get(i); OfficeDocument.Page source = document.pages.get(i);
            check(actual == metadata.get(i) && actual.y == y && actual.width == source.width && actual.height == source.height
                    && actual.background == source.background, "Stable full slide geometry/background at page " + (i + 1));
            y += source.height + 16;
        }
        check(renderer.height == y - 16, "Full document height excludes a trailing gap");
    }

    private static void ready(OfficeRenderer renderer, int first, int last) {
        for (int i = 0; i < renderer.pages.size(); i++) {
            OfficeRenderer.Page page = renderer.pages.get(i);
            check(page.ready == (i >= first && i <= last), "Prepared window " + first + ".." + last + ", page " + i);
            check(page.ready || page.elements.isEmpty(), "Evicted page has no retained elements: " + i);
        }
    }

    private static Bitmap pageBitmap(OfficeRenderer renderer, int index, Map<String, Bitmap> images) {
        OfficeRenderer.Page page = renderer.pages.get(index);
        Bitmap bitmap = Bitmap.createBitmap((int) page.width, (int) page.height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.translate(0, -page.y);
        renderer.draw(canvas, page.y, page.y + page.height, images);
        return bitmap;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void measure(OfficePreviewView view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
