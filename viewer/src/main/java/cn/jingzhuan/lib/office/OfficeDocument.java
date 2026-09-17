package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

/** Internal display model. Coordinates and font sizes are in points. */
final class OfficeDocument {
    enum Kind { DOCX, PPTX }
    enum Type { TEXT, IMAGE, RECT, ELLIPSE, LINE, TABLE }
    Kind kind;
    float width = 595;
    final List<Page> pages = new ArrayList<>();
    final List<Element> blocks = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    void warn(String message) {
        if (!warnings.contains(message)) warnings.add(message);
    }

    static final class Page {
        float width = 720, height = 405;
        int background = 0xffffffff;
        final List<Element> elements = new ArrayList<>();
    }

    static final class Element {
        Type type = Type.TEXT;
        float x, y, width, height, rotation;
        float padding = 4;
        int fill = 0x00000000, stroke = 0x00000000;
        float strokeWidth = 1;
        Bitmap image;
        final List<Paragraph> paragraphs = new ArrayList<>();
        final List<List<List<Paragraph>>> rows = new ArrayList<>();
        final List<Float> columnWidths = new ArrayList<>();
    }

    static final class Paragraph {
        final List<Run> runs = new ArrayList<>();
        int alignment;
        float before, after = 6, indent;
        String bullet = "";
    }

    static final class Run {
        String text = "";
        float size = 12;
        boolean bold, italic, underline;
        int color = 0xff202124;

        Run copy() {
            Run r = new Run();
            r.text = text; r.size = size; r.bold = bold;
            r.italic = italic; r.underline = underline; r.color = color;
            return r;
        }
    }
}
