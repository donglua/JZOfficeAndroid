package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
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
        if (!wanted.equals(parts)) {
            revision++;
            wanted.clear(); wanted.addAll(parts);
            long limit = pixelLimit();
            // Published bitmaps may still be referenced by RenderThread; drop ownership without recycling them.
            Iterator<Map.Entry<String, Bitmap>> entries = bitmaps.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Bitmap> entry = entries.next();
                Bitmap bitmap = entry.getValue();
                if (!wanted.contains(entry.getKey()) || (bitmap != null && (long) bitmap.getWidth() * bitmap.getHeight() > limit)) entries.remove();
            }
        }
        loadNext();
    }

    private long pixelLimit() { return MAX_PIXELS / Math.max(1, wanted.size()); }

    private void loadNext() {
        if (closed || suspended || loading) return;
        String missing = null;
        for (String part : wanted) if (!bitmaps.containsKey(part)) { missing = part; break; }
        if (missing == null) return;
        final String part = missing;
        final int token = revision;
        final long limit = pixelLimit();
        loading = true;
        worker.execute(() -> {
            Bitmap bitmap = null;
            Exception error = null;
            try {
                bitmap = source.image(part, limit);
                if (bitmap == null) error = new IOException("Unsupported image format; a placeholder is shown");
            }
            catch (IOException | RuntimeException failure) { error = failure; }
            catch (OutOfMemoryError failure) { error = new IOException("Not enough memory to decode image", failure); }
            final Bitmap decoded = bitmap;
            final Exception failure = error;
            main.post(() -> {
                loading = false;
                if (closed || suspended || token != revision) {
                    if (decoded != null) decoded.recycle();
                } else {
                    bitmaps.put(part, decoded);
                    changed.run();
                    if (failure != null) listener.onError(failure);
                }
                loadNext();
            });
        });
    }

    void suspend() {
        suspended = true; revision++;
        wanted.clear(); bitmaps.clear();
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
