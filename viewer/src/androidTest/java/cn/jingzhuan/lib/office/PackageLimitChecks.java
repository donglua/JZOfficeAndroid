package cn.jingzhuan.lib.office;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class PackageLimitChecks {
    static String run(Context context) throws Exception {
        StringBuilder log = new StringBuilder();
        verify(context, 48, 0, null, log);
        verify(context, 96, 1, null, log);
        verify(context, 65, 0, "64 MiB input limit", log);
        verify(context, 129, 1, "128 MiB limit", log);
        File[] cached = context.getCacheDir().listFiles((dir, name) -> name.startsWith("jz-office-"));
        if (cached == null || cached.length != 0) throw new AssertionError("Package limit checks leaked temporary files");
        return log.append("PASS Package limit temporary files removed\n").toString();
    }

    private static void verify(Context context, int paddingMiB, int level, String expectedError, StringBuilder log) throws Exception {
        File file = File.createTempFile("package-limit-", ".docx", context.getCacheDir());
        try {
            try (ZipInputStream input = new ZipInputStream(context.getAssets().open("sample.docx"));
                 ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
                byte[] buffer = new byte[32768];
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.closeEntry();
                }
                Arrays.fill(buffer, (byte) 0);
                output.setLevel(level);
                output.putNextEntry(new ZipEntry("unused/padding.bin"));
                for (long remaining = paddingMiB * 1024L * 1024; remaining > 0;) {
                    int count = (int) Math.min(remaining, buffer.length);
                    output.write(buffer, 0, count); remaining -= count;
                }
                output.closeEntry();
            }
            try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(file))) {
                if (expectedError != null) throw new AssertionError("Oversized package accepted: " + paddingMiB);
                OfficeDocument doc = DocumentDecoder.decode(NativeCore.parse(pkg.file.getAbsolutePath()));
                if (doc.kind != OfficeDocument.Kind.DOCX || doc.blocks.isEmpty()) throw new AssertionError("Large document content lost");
                log.append("PASS URI + JNI decode with ").append(paddingMiB).append(" MiB padding; input bytes=").append(file.length()).append('\n');
            } catch (IOException error) {
                if (expectedError == null || error.getMessage() == null || !error.getMessage().contains(expectedError)) throw error;
                log.append("PASS Rejected ").append(paddingMiB).append(" MiB padding: ").append(error.getMessage()).append('\n');
            }
        } finally { file.delete(); }
    }
}
