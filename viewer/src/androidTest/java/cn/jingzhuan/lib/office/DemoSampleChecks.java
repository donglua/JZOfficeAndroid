package cn.jingzhuan.lib.office;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

final class DemoSampleChecks {
    private static final String PACKAGE = "cn.jingzhuan.lib.office.demo";

    private DemoSampleChecks() { }

    static String run(Instrumentation instrumentation) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "am start -S -W -n " + PACKAGE + "/.MainActivity -a android.intent.action.MAIN");
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
        await(instrumentation, "Samples");
        capture(instrumentation, "demo-samples-list");
        String[] names = instrumentation.getTargetContext().getAssets().list("samples");
        Arrays.sort(names);
        int checked = 0;
        for (String name : names) {
            if (!name.endsWith(".docx") && !name.endsWith(".pptx") && !name.endsWith(".xlsx")) continue;
            Uri uri = Uri.parse("content://" + instrumentation.getTargetContext().getPackageName() + ".fixtures/samples/" + name);
            OfficeDocument document;
            try (OfficePackage source = OfficePackage.open(instrumentation.getTargetContext(), uri)) {
                document = source.document;
            }
            if (checked > 0) click(instrumentation, "Open sample");
            await(instrumentation, "Samples");
            for (int count = 0; find(root(instrumentation), name) == null && count < 20; count++) {
                if (!scroll(root(instrumentation))) break;
                instrumentation.getUiAutomation().waitForIdle(100, 3000);
            }
            click(instrumentation, name);
            await(instrumentation, name);
            String format = document.kind.name();
            int pages = document.kind == OfficeDocument.Kind.PPTX ? document.pages.size()
                : document.kind == OfficeDocument.Kind.XLSX ? document.sheets.size() : 1;
            for (int page = 1; page <= pages; page++) {
                if (page > 1) click(instrumentation, document.kind == OfficeDocument.Kind.XLSX
                    ? document.sheets.get(page - 1).name : "Next slide");
                await(instrumentation, format + (document.kind == OfficeDocument.Kind.DOCX
                    ? "  |  Continuous" : "  |  " + page + " / " + pages));
                capture(instrumentation, "demo-sample-" + name + "-page" + page);
            }
            checked++;
        }
        if (checked != 3) throw new AssertionError("Expected exactly three demo samples, got " + checked);
        return "PASS demo picker, sample provider, loaded format, page navigation and screenshots for " + checked + " samples\n";
    }

    private static AccessibilityNodeInfo root(Instrumentation instrumentation) {
        return instrumentation.getUiAutomation().getRootInActiveWindow();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (TextUtils.equals(PACKAGE, node.getPackageName()) && (TextUtils.equals(label, node.getContentDescription())
            || text != null && (TextUtils.equals(label, text) || text.toString().startsWith(label + "  |  ")))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(node.getChild(i), label);
            if (match != null) return match;
        }
        return null;
    }

    private static void await(Instrumentation instrumentation, String label) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(root(instrumentation), label) != null) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Demo state not reached: " + label);
    }

    private static void click(Instrumentation instrumentation, String label) {
        await(instrumentation, label);
        AccessibilityNodeInfo node = find(root(instrumentation), label);
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw new AssertionError("Demo click failed: " + label);
        }
    }

    private static boolean scroll(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
        return false;
    }

    private static void capture(Instrumentation instrumentation, String name) throws Exception {
        instrumentation.getUiAutomation().waitForIdle(500, 3000);
        // Accessibility can publish the new page before its surface reaches the screen.
        SystemClock.sleep(300);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Demo screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(), name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Demo capture failed");
        } finally { bitmap.recycle(); }
    }
}
