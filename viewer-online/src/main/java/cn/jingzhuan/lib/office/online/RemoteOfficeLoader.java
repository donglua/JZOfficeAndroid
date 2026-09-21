package cn.jingzhuan.lib.office.online;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import cn.jingzhuan.lib.office.OfficeCache;
import cn.jingzhuan.lib.office.OfficePreviewView;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class RemoteOfficeLoader implements Closeable {
    public interface Listener {
        void onProgress(long bytesRead, long totalBytes);
        void onDownloaded(RemoteOfficeDownloader.Result result);
        void onLoaded(OfficePreviewView.Info info);
        void onError(Exception error);
    }

    public interface CacheListener {
        void onCleared(OfficeCache.ClearResult result);
    }

    private static final ExecutorService CLEANER = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "jz-office-cache"));
    private static final ExecutorService CANCELLER = Executors.newCachedThreadPool(runnable -> daemon(runnable, "jz-office-cancel"));

    public static final class Task implements RemoteOfficeDownloader.Cancellation {
        private final RemoteOfficeLoader owner;
        private volatile boolean cancelled;
        private volatile boolean complete;
        private Future<?> future;
        private OfficeCache.Entry source;
        private Runnable cancellationAction;

        private Task(RemoteOfficeLoader owner) { this.owner = owner; }

        public void cancel() {
            requireMainThread();
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                complete = true;
                if (cancellationAction != null) CANCELLER.execute(cancellationAction);
                cancellationAction = null;
            }
            if (future != null) future.cancel(true);
            if (owner.current == this) {
                owner.current = null;
                owner.preview.clear();
            }
            releaseSource();
        }

        @Override public boolean isCancelled() { return cancelled; }
        public boolean isComplete() { return complete; }

        @Override public synchronized void onCancel(Runnable action) {
            if (cancelled) {
                if (action != null) CANCELLER.execute(action);
            } else cancellationAction = action;
        }

        private void releaseSource() {
            if (source != null) { source.close(); source = null; }
        }
    }

    private final File cacheDir;
    private final RemoteOfficeDownloader downloader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), runnable -> daemon(runnable, "jz-office-online"));
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            if (current != null) current.cancel();
        }
    };
    private OfficePreviewView preview;
    private Task current;
    private boolean closed;

    public RemoteOfficeLoader(Context context) {
        this(context, new HttpUrlConnectionOfficeDownloader());
    }

    public RemoteOfficeLoader(Context context, RemoteOfficeDownloader downloader) {
        if (context == null || downloader == null) throw new IllegalArgumentException("Context and downloader are required");
        cacheDir = context.getApplicationContext().getCacheDir();
        this.downloader = downloader;
        CLEANER.execute(() -> OfficeCache.clear(cacheDir));
    }

    public Task open(OfficePreviewView preview, RemoteOfficeRequest request, Listener listener) {
        requireMainThread();
        if (preview == null || request == null || listener == null) throw new IllegalArgumentException("Preview, request and listener are required");
        if (closed) throw new IllegalStateException("RemoteOfficeLoader is closed");
        if (this.preview != null && this.preview != preview) throw new IllegalArgumentException("Use one loader per preview");
        if (this.preview == null) {
            this.preview = preview;
            preview.addOnAttachStateChangeListener(attachment);
        }
        if (current != null) current.cancel();
        preview.clear();
        Task task = new Task(this);
        current = task;
        task.future = worker.submit(() -> downloadAndOpen(task, request, listener));
        return task;
    }

    public void clearCache(CacheListener listener) {
        clearCache(cacheDir, listener);
    }

    public static void clearCache(Context context, CacheListener listener) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        clearCache(context.getApplicationContext().getCacheDir(), listener);
    }

    private static void clearCache(File directory, CacheListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        Handler main = new Handler(Looper.getMainLooper());
        CLEANER.execute(() -> {
            OfficeCache.ClearResult result = OfficeCache.clear(directory);
            main.post(() -> listener.onCleared(result));
        });
    }

    private void downloadAndOpen(Task task, RemoteOfficeRequest request, Listener listener) {
        OfficeCache.Entry source = null;
        boolean transferred = false;
        try {
            if (task.isCancelled()) return;
            source = OfficeCache.create(cacheDir);
            RemoteOfficeDownloader.Result result = downloader.download(request, source.getFile(),
                (bytes, total) -> main.post(() -> {
                    if (isActive(task) && !task.complete) listener.onProgress(bytes, total);
                }), task);
            if (task.isCancelled()) return;
            OfficeCache.Entry completed = source;
            transferred = main.post(() -> {
                if (!isActive(task)) { completed.close(); return; }
                task.source = completed;
                listener.onDownloaded(result);
                if (!isActive(task)) return;
                preview.open(Uri.fromFile(completed.getFile()), new OfficePreviewView.Listener() {
                    @Override public void onLoaded(OfficePreviewView.Info info) {
                        task.releaseSource();
                        if (!isActive(task)) return;
                        task.complete = true;
                        listener.onLoaded(info);
                    }

                    @Override public void onError(Exception error) {
                        task.releaseSource();
                        if (!isActive(task)) return;
                        task.complete = true;
                        listener.onError(error);
                    }
                });
            });
        } catch (IOException | RuntimeException error) {
            main.post(() -> {
                if (!isActive(task)) return;
                task.complete = true;
                listener.onError(error);
            });
        } finally {
            if (!transferred && source != null) source.close();
        }
    }

    private boolean isActive(Task task) {
        return current == task && !task.isCancelled() && !closed;
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        if (current != null) current.cancel();
        if (preview != null) preview.removeOnAttachStateChangeListener(attachment);
        preview = null;
        worker.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call on the main thread");
    }
}
