package cn.jingzhuan.lib.office.online;

import java.io.File;
import java.io.IOException;

public interface RemoteOfficeDownloader {
    /** Runs on a worker thread. The caller owns destination cleanup, including partial files on failure. */
    Result download(RemoteOfficeRequest request, File destination, Progress progress, Cancellation cancellation) throws IOException;

    interface Progress {
        /** Called on the download thread; totalBytes is -1 when unknown. */
        void onProgress(long bytesRead, long totalBytes);
    }

    interface Cancellation {
        boolean isCancelled();

        /**
         * Atomically replaces the current action; null unregisters it. Dispatch a non-null action
         * even if already cancelled. Actions may block, so cancellation must dispatch off the UI thread.
         */
        void onCancel(Runnable action);
    }

    final class Result {
        private final String displayName;
        private final String contentType;
        private final long bytesRead;

        public Result(String displayName, String contentType, long bytesRead) {
            this.displayName = displayName;
            this.contentType = contentType;
            this.bytesRead = bytesRead;
        }

        public String getDisplayName() { return displayName; }
        public String getContentType() { return contentType; }
        public long getBytesRead() { return bytesRead; }
    }
}
