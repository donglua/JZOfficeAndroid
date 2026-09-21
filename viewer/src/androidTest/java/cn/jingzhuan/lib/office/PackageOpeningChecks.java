package cn.jingzhuan.lib.office;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PackageOpeningChecks {
    private static final byte[] DATA = {0, 1, 2, 3, 127, (byte) 255};
    private static final String DOCUMENT = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:body><w:p><w:r><w:t>Package opening</w:t></w:r></w:p></w:body></w:document>";
    private static final String MISSING_END = "Invalid Office ZIP: invalid Zip archive: Missing ZIP end record";

    static String run(Context context) throws Exception {
        OfficeCache.create(context.getCacheDir()).close();
        File directory = new File(context.getCacheDir(), "office-preview");
        OfficeCache.create(context.getCacheDir()).close();
        Set<String> original = cached(context);
        File unrelated = File.createTempFile("opening-unrelated-", ".bin", directory);
        File source = null, rejected = null;
        StringBuilder log = new StringBuilder();
        try {
            write(unrelated, DATA);
            Set<String> baseline = cached(context);
            source = File.createTempFile("package-opening-", ".docx", context.getCacheDir());
            rejected = File.createTempFile("package-rejected-", ".zip", context.getCacheDir());
            byte[] valid = fixture(DOCUMENT, 0);
            write(source, valid);
            accept(context, source, valid, baseline);
            log.append("PASS Minimal DOCX decoded on open; copied data and repeated part reads preserved\n");

            check(reject(context, Uri.parse("file:///missing"), null, "missing file", baseline, log)
                instanceof FileNotFoundException, "Missing file must fail while opening its stream");
            reject(context, Uri.parse("content://" + context.getPackageName() + ".fixtures/broken"),
                MISSING_END, "broken content pipe", baseline, log);
            reject(context, rejected, new byte[] {1, 2, 3}, MISSING_END, "malformed ZIP", baseline, log);
            reject(context, rejected, Arrays.copyOf(valid, valid.length - 1), MISSING_END, "truncated ZIP", baseline, log);
            reject(context, rejected, rename(valid, "data/b.bin", "data/a.bin"),
                "Duplicate package entry", "duplicate names", baseline, log);
            reject(context, rejected, fixture(DOCUMENT, 4092),
                "Package exceeds 4096 entries", "4097 physical entries", baseline, log);

            int footer = valid.length - 22;
            reject(context, rejected, change(valid, footer + 16, 4, number(valid, footer + 16) + 1),
                "Invalid ZIP directory bounds", "invalid directory bounds", baseline, log);
            reject(context, rejected, change(valid, header(valid, "data/a.bin"), 4, 0),
                "Invalid ZIP directory entry", "invalid directory signature", baseline, log);
            reject(context, rejected, change(valid, footer + 10, 2, 0xffff),
                "ZIP64 packages are not supported", "ZIP64 sentinel", baseline, log);
            reject(context, rejected, change(valid, footer + 4, 2, 1),
                "Split ZIP packages are not supported", "split ZIP", baseline, log);
            byte[] expanded = declaredSize(valid, "data/a.bin", 128 * 1024 * 1024 + 1);
            expanded = declaredSize(expanded, "data/b.bin", 128 * 1024 * 1024 + 1);
            reject(context, rejected, expanded, "Expanded package exceeds 256 MiB",
                "expanded declared total", baseline, log);
            reject(context, rejected, rename(valid, "_rels/.rels", "_rels/xrels"),
                "Only DOCX, PPTX and XLSX packages are supported", "missing Office relationship", baseline, log);
            reject(context, rejected, fixture("<document><body></document>", 0),
                "Invalid Office XML: expected 'body' tag, not 'document' at 1:17", "malformed document XML", baseline, log);

            for (String part : new String[] {"data/a.xml", "data/a.bin"}) {
                byte[] bytes = part.endsWith(".xml") ? rename(valid, "data/a.bin", part) : valid;
                bytes = declaredSize(bytes, part, (part.endsWith(".xml") ? 4 : 16) * 1024 * 1024 + 1);
                write(rejected, bytes);
                try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(rejected))) {
                    try {
                        pkg.read(part);
                        throw new AssertionError("Java part limit accepted " + part);
                    } catch (IOException error) {
                        check("Document part or read budget exceeded".equals(error.getMessage()),
                            "Java part limit error: " + error);
                    }
                    check(Arrays.equals(DATA, pkg.read("data/b.bin")), "Other parts remain readable after read rejection");
                } finally { unchanged(context, baseline, rejected, bytes); }
                log.append("PASS Java read limit retained for ").append(part).append('\n');
            }

            try {
                Thread.currentThread().interrupt();
                check(reject(context, Uri.fromFile(source), "Document load cancelled", "interrupted open", baseline, log)
                    instanceof InterruptedIOException, "Cancelled open must throw InterruptedIOException");
            } finally { Thread.interrupted(); }
            accept(context, source, valid, baseline);
            check(Arrays.equals(DATA, read(unrelated)), "Unrelated cache entry contents preserved");
            return log.append("PASS Successful reopen after rejection and cancellation; source and cache baseline preserved\n").toString();
        } finally {
            try {
                if (source != null) check(source.delete(), "Remove owned source fixture");
                if (rejected != null) check(rejected.delete(), "Remove owned rejection fixture");
            } finally {
                check(unrelated.delete(), "Remove owned unrelated-cache fixture");
                check(original.equals(cached(context)), "Restore original cache baseline");
            }
        }
    }

    private static void accept(Context context, File source, byte[] bytes, Set<String> baseline) throws Exception {
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(source))) {
            OfficeDocument document = pkg.document;
            check(document != null && document.kind == OfficeDocument.Kind.DOCX && document.blocks.size() == 1,
                "Open returns decoded DOCX");
            check("Package opening".equals(document.blocks.get(0).paragraphs.get(0).runs.get(0).text), "Decoded paragraph text");
            check(!source.getCanonicalFile().equals(pkg.file.getCanonicalFile()), "Open must copy its source");
            check(new File(context.getCacheDir(), "office-preview").getCanonicalFile().equals(pkg.file.getCanonicalFile().getParentFile()),
                "Copy belongs to document cache");
            check(Arrays.equals(bytes, read(pkg.file)), "Cached copy preserves source bytes");
            Set<String> openFiles = new HashSet<>(baseline);
            check(openFiles.add(pkg.file.getName()) && openFiles.equals(cached(context)), "One live cached copy");
            for (int pass = 0; pass < 3; pass++) {
                check(Arrays.equals(DOCUMENT.getBytes(StandardCharsets.UTF_8), pkg.read("word/document.xml")), "Repeated XML read");
                for (String part : new String[] {"data/a.bin", "data/b.bin"})
                    check(Arrays.equals(DATA, pkg.read(part)), "Repeated data read: " + part);
            }
        } finally { unchanged(context, baseline, source, bytes); }
    }

    private static void reject(Context context, File source, byte[] bytes, String expected, String label,
            Set<String> baseline, StringBuilder log) throws Exception {
        write(source, bytes);
        try { reject(context, Uri.fromFile(source), expected, label, baseline, log); }
        finally { unchanged(context, baseline, source, bytes); }
    }

    private static IOException reject(Context context, Uri uri, String expected, String label,
            Set<String> baseline, StringBuilder log) throws Exception {
        try (OfficePackage ignored = OfficePackage.open(context, uri)) {
            throw new AssertionError("Accepted " + label);
        } catch (IOException error) {
            check(expected == null || expected.equals(error.getMessage()), label + ": expected " + expected + ", got " + error);
            log.append("PASS Rejected ").append(label).append("; cached copy removed\n");
            return error;
        } finally { check(baseline.equals(cached(context)), "Cache baseline after " + label); }
    }

    private static void unchanged(Context context, Set<String> baseline, File source, byte[] bytes) throws IOException {
        check(baseline.equals(cached(context)), "No cached copy leaked");
        check(Arrays.equals(bytes, read(source)), "Original source fixture retained unchanged");
    }

    private static Set<String> cached(Context context) {
        String[] names = new File(context.getCacheDir(), "office-preview").list();
        check(names != null, "List document cache");
        return new HashSet<>(Arrays.asList(names));
    }

    private static byte[] fixture(String document, int paddingEntries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"bin\" ContentType=\"application/octet-stream\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>").getBytes(StandardCharsets.UTF_8));
            entry(zip, "_rels/.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rOffice\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
            entry(zip, "word/document.xml", document.getBytes(StandardCharsets.UTF_8));
            entry(zip, "data/a.bin", DATA);
            entry(zip, "data/b.bin", DATA);
            for (int i = 0; i < paddingEntries; i++) entry(zip, "unused/" + i, new byte[0]);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(946684800000L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] rename(byte[] source, String from, String to) {
        byte[] bytes = source.clone(), name = to.getBytes(StandardCharsets.UTF_8);
        int header = header(bytes, from), local = number(bytes, header + 42);
        check(name.length == from.getBytes(StandardCharsets.UTF_8).length, "Rename keeps ZIP record lengths");
        System.arraycopy(name, 0, bytes, header + 46, name.length);
        System.arraycopy(name, 0, bytes, local + 30, name.length);
        return bytes;
    }

    private static byte[] declaredSize(byte[] source, String name, int size) {
        int header = header(source, name), local = number(source, header + 42);
        return change(change(source, header + 24, 4, size), local + 22, 4, size);
    }

    private static int header(byte[] bytes, String name) {
        ByteBuffer zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int footer = bytes.length - 22;
        check(zip.getInt(footer) == 0x06054b50, "Fixture has a comment-free ZIP end record");
        for (int offset = zip.getInt(footer + 16); offset < footer;) {
            check(zip.getInt(offset) == 0x02014b50, "Fixture central directory signature");
            int length = zip.getShort(offset + 28) & 0xffff;
            if (name.equals(new String(bytes, offset + 46, length, StandardCharsets.UTF_8))) return offset;
            offset += 46 + length + (zip.getShort(offset + 30) & 0xffff) + (zip.getShort(offset + 32) & 0xffff);
        }
        throw new AssertionError("Missing fixture entry: " + name);
    }

    private static int number(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    private static byte[] change(byte[] source, int offset, int width, int value) {
        byte[] bytes = source.clone();
        for (int i = 0; i < width; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
        return bytes;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
