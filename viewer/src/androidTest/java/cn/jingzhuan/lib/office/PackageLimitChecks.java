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
        verify(context, "docx", 48, 0, null, log);
        verify(context, "pptx", 96, 0, null, log);
        verify(context, "pptx", 192, 1, null, log);
        verify(context, "pptx", 129, 0, "128 MiB input limit", log);
        verify(context, "pptx", 257, 1, "256 MiB limit", log);
        File[] cached = context.getCacheDir().listFiles((dir, name) -> name.startsWith("jz-office-"));
        if (cached == null || cached.length != 0) throw new AssertionError("Package limit checks leaked temporary files");
        return log.append("PASS Package limit temporary files removed\n").toString();
    }

    private static void verify(Context context, String format, int paddingMiB, int level, String expectedError, StringBuilder log) throws Exception {
        File file = File.createTempFile("package-limit-", "." + format, context.getCacheDir());
        try {
            try (ZipInputStream input = new ZipInputStream(context.getAssets().open("sample." + format));
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
                for (int part = 0, remaining = paddingMiB; remaining > 0; part++) {
                    output.putNextEntry(new ZipEntry("unused/padding" + part + ".bin"));
                    int partMiB = Math.min(remaining, 16);
                    for (long bytes = partMiB * 1024L * 1024; bytes > 0;) {
                        int count = (int) Math.min(bytes, buffer.length);
                        output.write(buffer, 0, count); bytes -= count;
                    }
                    output.closeEntry();
                    remaining -= partMiB;
                }
            }
            try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(file))) {
                if (expectedError != null) throw new AssertionError("Oversized package accepted: " + paddingMiB);
                OfficeDocument doc = DocumentDecoder.decode(NativeCore.parse(pkg.file.getAbsolutePath()));
                boolean hasContent = "pptx".equals(format)
                    ? doc.kind == OfficeDocument.Kind.PPTX && !doc.pages.isEmpty() && !doc.pages.get(0).elements.isEmpty()
                    : doc.kind == OfficeDocument.Kind.DOCX && !doc.blocks.isEmpty();
                if (!hasContent) throw new AssertionError("Large document content lost");
                for (int pass = 0; pass < 2; pass++) {
                    for (int part = 0, remaining = paddingMiB; remaining > 0; part++) {
                        int partMiB = Math.min(remaining, 16);
                        if (pkg.read("unused/padding" + part + ".bin").length != partMiB * 1024 * 1024)
                            throw new AssertionError("Large document part read lost data");
                        remaining -= partMiB;
                    }
                }
                log.append("PASS ").append(format).append(" URI + JNI decode and two content reads with ")
                    .append(paddingMiB).append(" MiB padding; input bytes=").append(file.length()).append('\n');
            } catch (IOException error) {
                if (expectedError == null || error.getMessage() == null || !error.getMessage().contains(expectedError)) throw error;
                log.append("PASS Rejected ").append(paddingMiB).append(" MiB padding: ").append(error.getMessage()).append('\n');
            }
        } finally { file.delete(); }
    }
}
