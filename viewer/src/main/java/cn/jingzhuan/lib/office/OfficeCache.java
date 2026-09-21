package cn.jingzhuan.lib.office;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Temporary document storage shared by the viewer and its download adapter in one app process. */
public final class OfficeCache {
    private static final Map<File, Integer> ACTIVE = new HashMap<>();
    private static final Set<File> INITIALIZED = new HashSet<>();

    private OfficeCache() { }

    public static final class ClearResult {
        public final long deletedBytes;
        public final int deletedFiles, skippedFiles, failedFiles;

        private ClearResult(long bytes, int deleted, int skipped, int failed) {
            deletedBytes = bytes; deletedFiles = deleted; skippedFiles = skipped; failedFiles = failed;
        }
    }

    /** Keep the entry open until all writing or reading through its file has finished. */
    public static final class Entry implements Closeable {
        private final File file;
        private boolean closed;

        private Entry(File file) { this.file = file; }
        public File getFile() { return file; }

        @Override public void close() {
            synchronized (OfficeCache.class) {
                if (closed) return;
                closed = true;
                int remaining = ACTIVE.get(file) - 1;
                if (remaining > 0) ACTIVE.put(file, remaining);
                else {
                    ACTIVE.remove(file);
                    file.delete();
                }
            }
        }
    }

    /** Call on a worker thread. The first use also removes files left by a previous process. */
    public static synchronized Entry create(File appCacheDir) throws IOException {
        File directory = directory(appCacheDir);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create document cache");
        if (INITIALIZED.add(directory)) clear(appCacheDir);
        File file = File.createTempFile("document-", ".tmp", directory);
        ACTIVE.put(file, 1);
        return new Entry(file);
    }

    /** Call on a worker thread. Active files are retained; other app caches are never traversed. */
    public static synchronized ClearResult clear(File appCacheDir) {
        File directory;
        try { directory = directory(appCacheDir); }
        catch (IOException error) { return new ClearResult(0, 0, 0, 1); }
        if (!directory.exists()) return new ClearResult(0, 0, 0, 0);
        File[] files = directory.listFiles();
        if (files == null) return new ClearResult(0, 0, 0, 1);
        long bytes = 0;
        int deleted = 0, skipped = 0, failed = 0;
        for (File file : files) {
            if (!file.getName().startsWith("document-") || !file.getName().endsWith(".tmp")) continue;
            try {
                if (!file.equals(file.getCanonicalFile()) || !file.isFile()) continue;
            } catch (IOException error) { failed++; continue; }
            if (ACTIVE.containsKey(file)) { skipped++; continue; }
            long length = file.length();
            if (file.delete()) { bytes += length; deleted++; }
            else if (file.exists()) failed++;
        }
        return new ClearResult(bytes, deleted, skipped, failed);
    }

    // Retain the download while OfficePackage copies it, even if the UI cancels its owner.
    static synchronized InputStream openInput(File source) throws IOException {
        File file = source.getCanonicalFile();
        FileInputStream input = new FileInputStream(file);
        Integer references = ACTIVE.get(file);
        if (references == null) return input;
        ACTIVE.put(file, references + 1);
        Entry retained = new Entry(file);
        return new FilterInputStream(input) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { retained.close(); }
            }
        };
    }

    private static File directory(File appCacheDir) throws IOException {
        File directory = new File(appCacheDir.getCanonicalFile(), "office-preview");
        if (!directory.equals(directory.getCanonicalFile())) throw new IOException("Document cache must not be a symbolic link");
        return directory;
    }
}
