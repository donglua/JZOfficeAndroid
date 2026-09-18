package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns a document's image source; cache state belongs to the main thread. */
final class OfficeImages {
    static final long MAX_PIXELS = 8_000_000;
    final Map<String, Bitmap> bitmaps = new HashMap<>();
    final Set<String> wanted = new LinkedHashSet<>();
    private final Set<String> resizePending = new LinkedHashSet<>();
    private final Map<String, Long> nextPixels = new HashMap<>();
    boolean loading;
    private final OfficePackage source;
    private final Runnable changed;
    private final OfficePreviewView.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private boolean closed, suspended;
    private int revision;

    OfficeImages(OfficePackage source, Runnable changed, OfficePreviewView.Listener listener) {
        this.source = source; this.changed = changed; this.listener = listener;
    }

    void request(Set<String> parts) {
        if (closed || suspended) return;
        if (!new ArrayList<>(wanted).equals(new ArrayList<>(parts))) {
            revision++;
            wanted.clear(); wanted.addAll(parts);
            long limit = pixelLimit();
            // Published bitmaps may still be referenced by RenderThread; drop ownership without recycling them.
            Iterator<Map.Entry<String, Bitmap>> entries = bitmaps.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Bitmap> entry = entries.next();
                if (!wanted.contains(entry.getKey())) {
                    nextPixels.remove(entry.getKey()); resizePending.remove(entry.getKey()); entries.remove();
                } else if (pixels(entry.getValue()) > limit) resizePending.add(entry.getKey());
                else resizePending.remove(entry.getKey());
            }
        }
        loadNext();
    }

    private long pixelLimit() { return MAX_PIXELS / Math.max(1, wanted.size()); }

    private static long pixels(Bitmap bitmap) { return bitmap == null ? 0 : (long) bitmap.getWidth() * bitmap.getHeight(); }

    private void loadNext() {
        if (closed || suspended || loading) return;
        String missing = null;
        long budget = pixelLimit();
        long available = MAX_PIXELS;
        for (Bitmap bitmap : bitmaps.values()) available -= pixels(bitmap);
        // Make room before loading new entries, while keeping each old bitmap until replacement.
        for (String part : wanted) if (resizePending.contains(part)) { missing = part; break; }
        if (missing == null) for (String part : wanted) {
            if (!bitmaps.containsKey(part)) { missing = part; break; }
        }
        if (missing == null) {
            // Reuse the budget left by small images; keep the old pixels visible during an upgrade.
            for (String part : wanted) {
                Long needed = nextPixels.get(part);
                long limit = available + pixels(bitmaps.get(part));
                if (needed != null && needed <= limit) { missing = part; budget = limit; break; }
            }
        }
        if (missing == null) return;
        budget = Math.min(budget, available + pixels(bitmaps.get(missing)));
        if (budget <= 0) return;
        final String part = missing;
        final int token = revision;
        final long limit = budget;
        loading = true;
        worker.execute(() -> {
            OfficePackage.Image result = null;
            Exception error = null;
            try {
                result = source.image(part, limit);
                if (result.bitmap == null) error = new IOException("Unsupported image format; a placeholder is shown");
            }
            catch (IOException | RuntimeException failure) { error = failure; }
            catch (OutOfMemoryError failure) { error = new IOException("Not enough memory to decode image", failure); }
            final Bitmap decoded = result == null ? null : result.bitmap;
            final long upgradePixels = result == null ? Long.MAX_VALUE : result.nextPixels;
            final Exception failure = error;
            main.post(() -> {
                loading = false;
                if (closed || suspended || token != revision) {
                    if (decoded != null) decoded.recycle();
                } else {
                    if (decoded != null || !bitmaps.containsKey(part)) bitmaps.put(part, decoded);
                    resizePending.remove(part);
                    nextPixels.put(part, upgradePixels);
                    changed.run();
                    if (failure != null) listener.onError(failure);
                }
                loadNext();
            });
        });
    }

    void suspend() {
        suspended = true; revision++;
        wanted.clear(); bitmaps.clear(); resizePending.clear(); nextPixels.clear();
    }

    void resume() { suspended = false; }

    void close() {
        if (closed) return;
        closed = true; suspend();
        // Close only after an in-flight decode has stopped reading the ZIP.
        worker.execute(() -> {
            try { source.close(); }
            catch (IOException error) { Log.w("JZOffice", "Unable to close document image source", error); }
        });
        worker.shutdown();
    }
}
