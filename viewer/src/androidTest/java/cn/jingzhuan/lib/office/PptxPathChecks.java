package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

final class PptxPathChecks {
    private static final int ORANGE = 0xffff9900;
    private static final int PURPLE = 0xff7b3ff2;
    private static final int TEAL = 0xff2a9d8f;
    private static final int MAGENTA = 0xffcc33aa;

    private PptxPathChecks() { }

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainChecked(() -> capture(context, document()));
        return "PASS PPTX custom paths, gradients, fill/stroke masks, holes, flips and shader reset pixels\n"
            + "SCREENSHOTS pptx-path-synthetic.png\n";
    }

    private static void capture(Context context, OfficeDocument document) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page page = renderer.pages.get(0);
        Bitmap bitmap = Bitmap.createBitmap(Math.round(page.width), Math.round(page.height), Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(bitmap), 0, page.height, Collections.emptyMap());
            int left = bitmap.getPixel(24, 40);
            int right = bitmap.getPixel(96, 40);
            check(Color.alpha(left) == 255 && Color.alpha(right) == 255, "Gradient path ignores transparent solid fill alpha");
            check(Color.red(left) > Color.blue(left) + 60, "Gradient path red stop reaches left edge");
            check(Color.blue(right) > Color.red(right) + 60, "Gradient path blue stop reaches right edge");
            color(bitmap, 145, 30, Color.GREEN, "Plain rectangle after gradient has no stale shader");
            color(bitmap, 55, 128, ORANGE, "Cubic/quad custom path paints its fill");
            color(bitmap, 170, 88, PURPLE, "Nonzero outer path remains filled");
            color(bitmap, 190, 110, Color.WHITE, "Opposite winding subpath cuts a hole");
            color(bitmap, 258, 90, Color.BLACK, "Stroke-only path paints outline");
            color(bitmap, 275, 110, Color.WHITE, "Stroke-only path leaves fill empty");
            color(bitmap, 275, 170, TEAL, "Fill-only path paints interior");
            color(bitmap, 258, 152, TEAL, "Fill-only path suppresses stroke color");
            color(bitmap, 112, 190, MAGENTA, "Transformed flipped path moves to mirrored side");
            color(bitmap, 140, 190, Color.WHITE, "Transformed flipped path clears old side");
            File file = new File(context.getFilesDir(), "pptx-path-synthetic.png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static OfficeDocument document() {
        OfficeDocument document = new OfficeDocument();
        document.kind = OfficeDocument.Kind.PPTX;
        document.width = 320;
        OfficeDocument.Page page = new OfficeDocument.Page();
        page.width = 320;
        page.height = 220;
        page.background = Color.WHITE;
        document.pages.add(page);

        OfficeDocument.Element gradient = pathElement(10, 10, 100, 60, Color.TRANSPARENT, Color.TRANSPARENT, 0);
        gradient.fillGradient = gradientFill();
        gradient.paths.add(path(true, false,
            command("MOVE", 0, 0), command("LINE", 100, 0), command("LINE", 100, 60),
            command("LINE", 0, 60), command("CLOSE")));
        page.elements.add(gradient);

        OfficeDocument.Element plain = new OfficeDocument.Element();
        plain.type = OfficeDocument.Type.RECT;
        plain.x = 125;
        plain.y = 10;
        plain.width = 40;
        plain.height = 40;
        plain.fill = Color.GREEN;
        page.elements.add(plain);

        OfficeDocument.Element curve = pathElement(10, 85, 120, 70, ORANGE, Color.TRANSPARENT, 0);
        curve.paths.add(path(true, false,
            command("MOVE", 10, 60), command("CUBIC", 20, 0, 95, 0, 110, 60),
            command("QUAD", 60, 35, 10, 60), command("CLOSE")));
        page.elements.add(curve);

        OfficeDocument.Element donut = pathElement(150, 80, 80, 80, PURPLE, Color.TRANSPARENT, 0);
        donut.paths.add(path(true, false,
            command("MOVE", 0, 0), command("LINE", 80, 0), command("LINE", 80, 80),
            command("LINE", 0, 80), command("CLOSE"),
            command("MOVE", 20, 20), command("LINE", 20, 60), command("LINE", 60, 60),
            command("LINE", 60, 20), command("CLOSE")));
        page.elements.add(donut);

        OfficeDocument.Element strokeOnly = pathElement(250, 85, 50, 50, Color.RED, Color.BLACK, 4);
        strokeOnly.paths.add(path(false, true,
            command("MOVE", 5, 5), command("LINE", 45, 5), command("LINE", 45, 45),
            command("LINE", 5, 45), command("CLOSE")));
        page.elements.add(strokeOnly);

        OfficeDocument.Element fillOnly = pathElement(250, 145, 50, 50, TEAL, Color.BLACK, 8);
        fillOnly.paths.add(path(true, false,
            command("MOVE", 5, 5), command("LINE", 45, 5), command("LINE", 45, 45),
            command("LINE", 5, 45), command("CLOSE")));
        page.elements.add(fillOnly);

        OfficeDocument.Element flipped = pathElement(70, 170, 50, 40, MAGENTA, Color.TRANSPARENT, 0);
        flipped.transform = new float[] {1, 0, 0, 1, 30, 0};
        flipped.flipH = true;
        flipped.paths.add(path(true, false,
            command("MOVE", 30, 0), command("LINE", 50, 20), command("LINE", 30, 40), command("CLOSE")));
        page.elements.add(flipped);
        return document;
    }

    private static OfficeDocument.Element pathElement(float x, float y, float width, float height,
                                                      int fill, int stroke, float strokeWidth) {
        OfficeDocument.Element element = new OfficeDocument.Element();
        element.type = OfficeDocument.Type.PATH;
        element.x = x;
        element.y = y;
        element.width = width;
        element.height = height;
        element.fill = fill;
        element.stroke = stroke;
        element.strokeWidth = strokeWidth;
        return element;
    }

    private static OfficeDocument.GradientFill gradientFill() {
        OfficeDocument.GradientFill fill = new OfficeDocument.GradientFill();
        fill.colors = new int[] {Color.RED, Color.BLUE};
        fill.positions = new float[] {0, 1};
        fill.angle = 0;
        fill.scaled = false;
        return fill;
    }

    private static OfficeDocument.Path path(boolean fill, boolean stroke, OfficeDocument.Command... commands) {
        OfficeDocument.Path path = new OfficeDocument.Path();
        path.fill = fill;
        path.stroke = stroke;
        Collections.addAll(path.commands, commands);
        return path;
    }

    private static OfficeDocument.Command command(String op, float... points) {
        OfficeDocument.Command command = new OfficeDocument.Command();
        command.op = op;
        command.points = points;
        return command;
    }
}
