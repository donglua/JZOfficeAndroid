package cn.jingzhuan.lib.office;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;

final class DemoSpreadsheetChecks {
    private static final String PACKAGE = "cn.jingzhuan.lib.office.demo";

    private DemoSpreadsheetChecks() { }

    static String run(Instrumentation instrumentation) throws Exception {
        String command = "am start -W -n " + PACKAGE + "/.MainActivity -a android.intent.action.VIEW"
            + " -d content://" + PACKAGE + ".samples/sample.xlsx"
            + " -t application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
        awaitPage(instrumentation, "1 / 2", "Overview");
        click(instrumentation, "Data");
        awaitPage(instrumentation, "2 / 2", "Data");
        capture(instrumentation, "demo-xlsx-data.png");
        click(instrumentation, "Overview");
        awaitPage(instrumentation, "1 / 2", "Overview");
        capture(instrumentation, "demo-xlsx-overview.png");
        return "PASS demo ACTION_VIEW, accessible tab clicks in both directions, selected state and page labels\n";
    }

    private static void click(Instrumentation instrumentation, String name) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(name)) {
            if (PACKAGE.contentEquals(node.getPackageName()) && name.contentEquals(node.getText())
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        throw new AssertionError("Demo tab click failed: " + name);
    }

    private static void awaitPage(Instrumentation instrumentation, String page, String tab) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                boolean selected = false, status = false;
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(tab)) {
                    if (PACKAGE.contentEquals(node.getPackageName()) && tab.contentEquals(node.getText()) && node.isSelected()) selected = true;
                }
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("XLSX")) {
                    if (PACKAGE.contentEquals(node.getPackageName()) && node.getText().toString().contains(page)) status = true;
                }
                if (selected && status) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Demo sheet state not reached: " + page + " " + tab);
    }

    private static void capture(Instrumentation instrumentation, String name) throws Exception {
        instrumentation.getUiAutomation().waitForIdle(100, 3000);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Demo screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(), name))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Demo capture failed");
        } finally { bitmap.recycle(); }
    }
}
