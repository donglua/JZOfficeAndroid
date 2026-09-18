package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class OfficePackage implements Closeable {
    static final long MAX_INPUT = 64L * 1024 * 1024;
    static final long MAX_EXPANDED = 128L * 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 4096;
    static final class Image {
        final Bitmap bitmap;
        final long nextPixels;
        Image(Bitmap bitmap, long nextPixels) { this.bitmap = bitmap; this.nextPixels = nextPixels; }
    }
    final File file;
    final ZipFile zip;
    private long readBytes;
    private final Map<String, Long> readSizes = new HashMap<>();

    private OfficePackage(File file, ZipFile zip) { this.file = file; this.zip = zip; }

    static OfficePackage open(Context context, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) throw new IOException("Only content:// and file:// URIs are supported");
        File file = File.createTempFile("jz-office-", ".zip", context.getCacheDir());
        ZipFile zip = null;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(file)) {
                if (input == null) throw new IOException("Provider returned no stream");
                byte[] buffer = new byte[32768];
                long size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if ((size += count) > MAX_INPUT) throw new IOException("Document exceeds 64 MiB input limit");
                    output.write(buffer, 0, count);
                }
            }
            zip = new ZipFile(file);
            long expanded = 0;
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            java.util.HashSet<String> names = new java.util.HashSet<>();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                if (++count > 4096 || !names.add(entry.getName())) throw new IOException("Invalid or oversized package");
                long size = entry.getSize();
                if (size < 0 || size > MAX_EXPANDED || (expanded += size) > MAX_EXPANDED) throw new IOException("Expanded document exceeds 128 MiB limit");
            }
            return new OfficePackage(file, zip);
        } catch (IOException | RuntimeException e) {
            if (zip != null) try { zip.close(); } catch (IOException ignored) { }
            file.delete();
            throw e;
        }
    }

    byte[] read(String part) throws IOException {
        checkCancelled();
        ZipEntry entry = zip.getEntry(part);
        if (entry == null) throw new IOException("Missing document part: " + part);
        long limit = part.endsWith(".xml") || part.endsWith(".rels") ? 4L * 1024 * 1024 : 16L * 1024 * 1024;
        if (entry.getSize() > limit) throw new IOException("Document part or read budget exceeded");
        Long recorded = readSizes.get(part);
        long charged = recorded == null ? 0 : recorded;
        long partBytes = 0;
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                checkCancelled();
                partBytes += n;
                if (partBytes > charged) {
                    readBytes += partBytes - charged;
                    charged = partBytes;
                    readSizes.put(part, charged);
                }
                if ((long) output.size() + n > limit || readBytes > 128L * 1024 * 1024) throw new IOException("Document part or read budget exceeded");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    Image image(String part, long pixelLimit) throws IOException {
        if (part == null) return new Image(null, Long.MAX_VALUE);
        byte[] bytes = read(part);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return new Image(null, Long.MAX_VALUE);
        options.inSampleSize = 1;
        while (sampledPixels(options) > pixelLimit
                || (options.outWidth + (long) options.inSampleSize - 1) / options.inSampleSize > MAX_IMAGE_DIMENSION
                || (options.outHeight + (long) options.inSampleSize - 1) / options.inSampleSize > MAX_IMAGE_DIMENSION) {
            if (options.inSampleSize >= 1 << 30) throw new IOException("Image dimensions exceed decode limit");
            options.inSampleSize *= 2;
        }
        long nextPixels = Long.MAX_VALUE;
        if (options.inSampleSize > 1) {
            options.inSampleSize /= 2;
            if ((options.outWidth + (long) options.inSampleSize - 1) / options.inSampleSize <= MAX_IMAGE_DIMENSION
                    && (options.outHeight + (long) options.inSampleSize - 1) / options.inSampleSize <= MAX_IMAGE_DIMENSION) {
                nextPixels = sampledPixels(options);
            }
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        checkCancelled();
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap != null && (long) bitmap.getWidth() * bitmap.getHeight() > pixelLimit) {
            bitmap.recycle();
            throw new IOException("Decoded image exceeds its visible-area pixel budget");
        }
        return new Image(bitmap, bitmap == null ? Long.MAX_VALUE : nextPixels);
    }

    private static long sampledPixels(BitmapFactory.Options options) {
        return ((options.outWidth + (long) options.inSampleSize - 1) / options.inSampleSize)
            * ((options.outHeight + (long) options.inSampleSize - 1) / options.inSampleSize);
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Document load cancelled");
    }

    @Override public void close() throws IOException {
        try { zip.close(); } finally { file.delete(); }
    }
}
