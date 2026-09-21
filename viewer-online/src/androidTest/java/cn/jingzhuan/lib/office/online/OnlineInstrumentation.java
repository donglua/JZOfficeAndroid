package cn.jingzhuan.lib.office.online;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import cn.jingzhuan.lib.office.OfficeCache;
import cn.jingzhuan.lib.office.OfficePreviewView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class OnlineInstrumentation extends Instrumentation {
    private OnlineTestActivity activity;
    private RemoteOfficeLoader loader;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            activity = (OnlineTestActivity) startActivitySync(new Intent(getTargetContext(), OnlineTestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            FakeDownloader downloader = new FakeDownloader();
            runOnMainSync(() -> loader = new RemoteOfficeLoader(activity, downloader));
            for (String format : new String[] {"docx", "pptx", "xlsx"}) {
                Load listener = new Load();
                runOnMainSync(() -> loader.open(activity.preview, request(format), listener));
                listener.await();
                check(listener.info.format.equalsIgnoreCase(format), "Remote sample format " + format);
                OfficeCache.ClearResult cleared = clear();
                check(cleared.failedFiles == 0, "Clear while displaying " + format);
                if (!format.equals("xlsx")) check(cleared.skippedFiles >= 1, "Displayed package is retained");
                runOnMainSync(() -> check(activity.preview.getPageCount() > 0, "Clear preserves loaded document"));
            }
            cancelledDownloadThenLocal(downloader);
            cancelInDownloadedCallback();
            runOnMainSync(() -> loader.close());
            awaitEmpty();
            check(clear().deletedBytes == 0, "Repeated cleanup is empty");
            result.putString("stream", "\nPASS online samples, active cache protection, cancellation/local replacement, reentrant cancellation, close cleanup\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "\nFAILED: " + android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) runOnMainSync(() -> {
                if (loader != null) loader.close();
                activity.finish();
            });
        }
        finish(code, result);
    }

    private void cancelledDownloadThenLocal(FakeDownloader downloader) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        downloader.started = started;
        downloader.release = release;
        AtomicInteger staleCallbacks = new AtomicInteger();
        AtomicReference<RemoteOfficeLoader.Task> task = new AtomicReference<>();
        runOnMainSync(() -> task.set(loader.open(activity.preview, request("docx"), new Load() {
            @Override public void onDownloaded(RemoteOfficeDownloader.Result result) { staleCallbacks.incrementAndGet(); }
            @Override public void onLoaded(OfficePreviewView.Info info) { staleCallbacks.incrementAndGet(); }
            @Override public void onError(Exception error) { staleCallbacks.incrementAndGet(); }
        })));
        check(started.await(5, TimeUnit.SECONDS), "Download starts");
        check(clear().skippedFiles == 1, "Clear skips active download");
        File local = File.createTempFile("online-local-", ".docx", activity.getCacheDir());
        copySample("docx", local);
        Load loaded = new Load();
        try {
            runOnMainSync(() -> {
                task.get().cancel();
                activity.preview.open(Uri.fromFile(local), new OfficePreviewView.Listener() {
                    @Override public void onLoaded(OfficePreviewView.Info info) { loaded.onLoaded(info); }
                    @Override public void onError(Exception error) { loaded.onError(error); }
                });
            });
            release.countDown();
            loaded.await();
            check(staleCallbacks.get() == 0 && task.get().isCancelled(), "Cancelled callbacks do not overwrite local preview");
            runOnMainSync(() -> activity.preview.clear());
        } finally { release.countDown(); local.delete(); downloader.started = downloader.release = null; }
    }

    private void cancelInDownloadedCallback() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger unexpected = new AtomicInteger();
        AtomicReference<RemoteOfficeLoader.Task> task = new AtomicReference<>();
        runOnMainSync(() -> task.set(loader.open(activity.preview, request("docx"), new Load() {
            @Override public void onDownloaded(RemoteOfficeDownloader.Result result) {
                task.get().cancel();
                done.countDown();
            }
            @Override public void onLoaded(OfficePreviewView.Info info) { unexpected.incrementAndGet(); }
            @Override public void onError(Exception error) { unexpected.incrementAndGet(); }
        })));
        check(done.await(10, TimeUnit.SECONDS), "Downloaded callback cancels task");
        waitForIdleSync();
        check(unexpected.get() == 0, "Reentrant cancellation stops preview handoff");
        runOnMainSync(() -> check(activity.preview.getPageCount() == 0, "Reentrant cancellation leaves preview empty"));
        awaitEmpty();
    }

    private OfficeCache.ClearResult clear() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OfficeCache.ClearResult> value = new AtomicReference<>();
        loader.clearCache(result -> {
            check(Looper.myLooper() == Looper.getMainLooper(), "Cache result on main thread");
            value.set(result); done.countDown();
        });
        check(done.await(5, TimeUnit.SECONDS), "Clear completes independently of download");
        return value.get();
    }

    private void awaitEmpty() throws Exception {
        File directory = new File(activity.getCacheDir(), "office-preview");
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            File[] files = directory.listFiles();
            if (files != null && files.length == 0) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Cache files were not released");
    }

    private RemoteOfficeRequest request(String format) {
        return RemoteOfficeRequest.builder("https://example.com/sample." + format).build();
    }

    private void copySample(String format, File file) throws Exception {
        try (InputStream input = getContext().getAssets().open("samples/sample." + format);
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[32768];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
    }

    private final class FakeDownloader implements RemoteOfficeDownloader {
        volatile CountDownLatch started, release;

        @Override public Result download(RemoteOfficeRequest request, File destination, Progress progress, Cancellation cancellation) throws java.io.IOException {
            try {
                String path = request.getUrl().getPath();
                copySample(path.substring(path.lastIndexOf('.') + 1), destination);
                CountDownLatch entered = started, gate = release;
                if (entered != null) {
                    entered.countDown();
                    if (!gate.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Test download timed out");
                }
                if (cancellation.isCancelled()) throw new InterruptedIOException("Cancelled");
                progress.onProgress(destination.length(), destination.length());
                return new Result(path, "application/octet-stream", destination.length());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            } catch (Exception error) { throw new java.io.IOException(error); }
        }
    }

    private static class Load implements RemoteOfficeLoader.Listener {
        private final CountDownLatch done = new CountDownLatch(1);
        OfficePreviewView.Info info;
        Exception error;
        @Override public void onProgress(long bytesRead, long totalBytes) { }
        @Override public void onDownloaded(RemoteOfficeDownloader.Result result) { }
        @Override public void onLoaded(OfficePreviewView.Info info) { this.info = info; done.countDown(); }
        @Override public void onError(Exception error) { this.error = error; done.countDown(); }
        void await() throws Exception {
            check(done.await(15, TimeUnit.SECONDS), "Preview callback arrives");
            if (error != null) throw error;
            check(info != null, "Document loaded");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
