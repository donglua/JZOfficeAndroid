package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.util.Collections;

final class PptxPresetChecks {
    private PptxPresetChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        String[] names = {"triangle", "rtTriangle", "diamond", "triangle", "rtTriangle", "triangle"};
        String[] labels = {"Triangle 三角形", "Right triangle 直角", "Diamond 菱形", "Adjusted 顶点偏移", "Flipped 水平翻转", "Rotated 旋转 90°"};
        String[] transforms = {"", "", "", "", " flipH=\"1\"", " rot=\"5400000\""};
        String[] colors = {"2277BB", "2A9D8F", "DD8844", "8855BB", "2A9D8F", "2277BB"};
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            int x = 20 + i % 3 * 240, y = i < 3 ? 55 : 245;
            shapes.append("<p:sp><p:spPr>").append(bounds(x, y, 160, 100, transforms[i]))
                .append("<a:prstGeom prst=\"").append(names[i]).append("\"><a:avLst>")
                .append(i == 3 ? "<a:gd name=\"adj\" fmla=\"val 25000\"/>" : "")
                .append("</a:avLst></a:prstGeom><a:solidFill><a:srgbClr val=\"").append(colors[i])
                .append("\"/></a:solidFill><a:ln w=\"25400\"><a:solidFill><a:srgbClr val=\"223344\"/>")
                .append("</a:solidFill></a:ln></p:spPr></p:sp>")
                .append("<p:sp><p:spPr>").append(bounds(x, i < 3 ? 15 : 180, 210, 24, ""))
                .append("<a:noFill/></p:spPr><p:txBody><a:bodyPr lIns=\"0\" rIns=\"0\" tIns=\"0\" bIns=\"0\"/>")
                .append("<a:p><a:r><a:rPr sz=\"1400\"/><a:t>").append(labels[i])
                .append("</a:t></a:r></a:p></p:txBody></p:sp>");
        }
        Context context = instrumentation.getTargetContext();
        File source = CompatibilityChecks.presentation(context, "presets", shapes.toString());
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(source))) {
            check(pkg.document.warnings.isEmpty(), "Supported presets avoid omitted-geometry warnings");
            long paths = pkg.document.pages.get(0).elements.stream().filter(e -> e.type == OfficeDocument.Type.PATH).count();
            check(paths == 6, "All six preset examples reach Java paths");
            instrumentation.runOnMainChecked(() -> {
                OfficeRenderer renderer = new OfficeRenderer();
                renderer.layout(pkg.document);
                Bitmap bitmap = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
                try {
                    renderer.draw(new Canvas(bitmap), 0, 405, Collections.emptyMap());
                    color(bitmap, 100, 130, 0xff2277bb, "Triangle interior");
                    color(bitmap, 30, 70, Color.WHITE, "Triangle excludes bounding-box corner");
                    color(bitmap, 100, 155, 0xff223344, "Triangle stroke");
                    color(bitmap, 280, 135, 0xff2a9d8f, "Right triangle interior");
                    color(bitmap, 400, 75, Color.WHITE, "Right triangle excludes upper-right corner");
                    color(bitmap, 600, 105, 0xffdd8844, "Diamond interior");
                    color(bitmap, 510, 65, Color.WHITE, "Diamond excludes bounding-box corner");
                    color(bitmap, 60, 265, 0xff8855bb, "Adjusted triangle apex");
                    color(bitmap, 120, 265, Color.WHITE, "Adjustment removes old apex");
                    color(bitmap, 400, 265, 0xff2a9d8f, "Flipped triangle interior");
                    color(bitmap, 280, 265, Color.WHITE, "Flip removes original interior");
                    color(bitmap, 610, 295, 0xff2277bb, "Rotated triangle interior");
                    color(bitmap, 650, 295, Color.WHITE, "Rotated triangle bounds");
                } finally { bitmap.recycle(); }
            });
            CompatibilityChecks.show(instrumentation, activity, source, "compat-presets-screen.png");
        } finally { check(source.delete(), "Delete preset fixture"); }
        return "PASS PPTX triangle/right-triangle/diamond through JNI, fill/stroke/adjustment/flip/rotation pixels and URI preview\n";
    }

    private static String bounds(int x, int y, int width, int height, String transform) {
        return "<a:xfrm" + transform + "><a:off x=\"" + x * 12700 + "\" y=\"" + y * 12700
            + "\"/><a:ext cx=\"" + width * 12700 + "\" cy=\"" + height * 12700 + "\"/></a:xfrm>";
    }
}
