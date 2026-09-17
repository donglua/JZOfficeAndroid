package cn.jingzhuan.lib.office;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

final class OfficeRenderer {
    static final class Element {
        OfficeDocument.Element source;
        final List<OfficeTextLayout.Block> texts = new ArrayList<>();
        final List<RectF> cells = new ArrayList<>();
        float x, y, width, height;
    }

    static final class Page {
        float y, width, height;
        int background;
        final List<Element> elements = new ArrayList<>();
    }

    final List<Page> pages = new ArrayList<>();
    float width, height;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    void clear() { pages.clear(); width = height = 0; }

    void layout(OfficeDocument document) {
        clear(); width = Math.max(1, document.width);
        if (document.kind == OfficeDocument.Kind.DOCX) {
            Page page = new Page(); page.width = width; page.background = 0xffffffff;
            float y = 32;
            for (OfficeDocument.Element source : document.blocks) {
                Element drawn = element(source, width - 64, true, document);
                drawn.x = 32; drawn.y = y;
                if (source.type == OfficeDocument.Type.IMAGE) drawn.x += (width - 64 - drawn.width) / 2;
                page.elements.add(drawn); y += drawn.height + 6;
            }
            page.height = Math.max(300, y + 32); pages.add(page); height = page.height;
        } else {
            for (OfficeDocument.Page source : document.pages) {
                Page page = new Page(); page.y = height;
                page.width = source.width; page.height = source.height; page.background = source.background;
                for (OfficeDocument.Element e : source.elements) page.elements.add(element(e, e.width, false, document));
                pages.add(page); height += page.height + 16;
            }
            height = Math.max(0, height - 16);
        }
    }

    private Element element(OfficeDocument.Element source, float available, boolean flow, OfficeDocument document) {
        Element d = new Element(); d.source = source; d.x = source.x; d.y = source.y;
        d.width = Math.max(1, flow ? (source.type == OfficeDocument.Type.TABLE && source.width > 0 ? Math.min(source.width, available) : available) : source.width);
        d.height = Math.max(1, source.height);
        if (source.type == OfficeDocument.Type.IMAGE && flow) {
            float w = source.width > 0 ? source.width : available;
            float h = source.height > 0 ? source.height : 100;
            d.width = Math.min(available, w); d.height = h * d.width / Math.max(1, w);
        } else if (source.type == OfficeDocument.Type.TABLE) {
            int cols = 0;
            for (List<List<OfficeDocument.Paragraph>> row : source.rows) cols = Math.max(cols, row.size());
            float total = 0;
            for (Float w : source.columnWidths) total += Math.max(1, w);
            float y = 0;
            for (List<List<OfficeDocument.Paragraph>> row : source.rows) {
                float x = 0, rowHeight = 20;
                int first = d.cells.size();
                for (int c = 0; c < cols; c++) {
                    float w = source.columnWidths.size() == cols && total > 0 ? d.width * Math.max(1, source.columnWidths.get(c)) / total : d.width / Math.max(1, cols);
                    List<OfficeDocument.Paragraph> cell = c < row.size() ? row.get(c) : Collections.emptyList();
                    rowHeight = Math.max(rowHeight, OfficeTextLayout.append(cell, x + 5, y + 5, Math.max(1, w - 10), d.texts) - y + 5);
                    d.cells.add(new RectF(x, y, x + w, y)); x += w;
                }
                for (int i = first; i < d.cells.size(); i++) d.cells.get(i).bottom = y + rowHeight;
                y += rowHeight;
            }
            if (flow) d.height = Math.max(1, y);
            else if (y > d.height) document.warn("Table content exceeds its slide bounds and is clipped");
        } else if (!source.paragraphs.isEmpty()) {
            float padding = flow ? 0 : Math.max(0, source.padding);
            float textHeight = OfficeTextLayout.append(source.paragraphs, padding, padding,
                Math.max(1, d.width - 2 * padding), d.texts) + padding;
            if (flow) d.height = Math.max(1, textHeight);
            else {
                if (textHeight > d.height + 1) document.warn("Text exceeds its slide box and is clipped");
                float remaining = Math.max(0, d.height - textHeight);
                float offset = source.verticalAlignment == OfficeDocument.VerticalAlignment.CENTER ? remaining / 2
                    : source.verticalAlignment == OfficeDocument.VerticalAlignment.BOTTOM ? remaining : 0;
                for (OfficeTextLayout.Block block : d.texts) block.y += offset;
            }
        }
        return d;
    }

    Set<String> visibleImages(float left, float top, float right, float bottom) {
        Set<String> parts = new LinkedHashSet<>();
        for (Page page : pages) {
            if (page.y + page.height < top || page.y > bottom) continue;
            float clippedLeft = Math.max(0, left), clippedRight = Math.min(page.width, right);
            float clippedTop = Math.max(page.y, top), clippedBottom = Math.min(page.y + page.height, bottom);
            if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) continue;
            for (Element e : page.elements) {
                if (e.source.image == null) continue;
                double angle = Math.toRadians(e.source.rotation);
                float halfWidth = (float) (Math.abs(Math.cos(angle)) * e.width + Math.abs(Math.sin(angle)) * e.height) / 2;
                float halfHeight = (float) (Math.abs(Math.sin(angle)) * e.width + Math.abs(Math.cos(angle)) * e.height) / 2;
                float cx = e.x + e.width / 2, cy = page.y + e.y + e.height / 2;
                if (cx + halfWidth > clippedLeft && cx - halfWidth < clippedRight && cy + halfHeight > clippedTop && cy - halfHeight < clippedBottom) parts.add(e.source.image);
            }
        }
        return parts;
    }

    void draw(Canvas canvas, float visibleTop, float visibleBottom, Map<String, Bitmap> images) {
        for (Page page : pages) {
            if (page.y + page.height < visibleTop || page.y > visibleBottom) continue;
            canvas.save(); canvas.translate(0, page.y); canvas.clipRect(0, 0, page.width, page.height);
            paint.setStyle(Paint.Style.FILL); paint.setColor(page.background);
            canvas.drawRect(0, 0, page.width, page.height, paint);
            for (Element e : page.elements) {
                if (e.source.rotation != 0 || (e.y + e.height >= visibleTop - page.y && e.y <= visibleBottom - page.y)) drawElement(canvas, e, images);
            }
            canvas.restore();
        }
    }

    private void drawElement(Canvas canvas, Element d, Map<String, Bitmap> images) {
        OfficeDocument.Element e = d.source;
        canvas.save(); canvas.translate(d.x, d.y); canvas.rotate(e.rotation, d.width / 2, d.height / 2);
        RectF rect = new RectF(0, 0, d.width, d.height);
        paint.setStyle(Paint.Style.FILL); paint.setColor(e.fill);
        if (e.type == OfficeDocument.Type.ELLIPSE) canvas.drawOval(rect, paint);
        else if (e.type != OfficeDocument.Type.LINE) canvas.drawRect(rect, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(e.strokeWidth); paint.setColor(e.stroke);
        if (e.type == OfficeDocument.Type.ELLIPSE) canvas.drawOval(rect, paint);
        else if (e.type == OfficeDocument.Type.LINE) canvas.drawLine(0, 0, e.width, e.height, paint);
        else if (e.stroke != 0) canvas.drawRect(rect, paint);
        canvas.clipRect(rect); paint.setStyle(Paint.Style.FILL);
        if (e.type == OfficeDocument.Type.IMAGE) {
            Bitmap bitmap = images.get(e.image);
            if (bitmap != null) {
                paint.setColor(0xffffffff);
                if (e.imageCrop != null) {
                    OfficeDocument.ImageCrop crop = e.imageCrop;
                    float fullWidth = d.width / (1 - crop.left - crop.right);
                    float fullHeight = d.height / (1 - crop.top - crop.bottom);
                    rect.set(-crop.left * fullWidth, -crop.top * fullHeight,
                        (1 - crop.left) * fullWidth, (1 - crop.top) * fullHeight);
                }
                canvas.drawBitmap(bitmap, null, rect, paint);
            } else { paint.setColor(0xffe0e0e0); canvas.drawRect(rect, paint); }
        }
        if (e.type == OfficeDocument.Type.TABLE) {
            paint.setColor(0xffb7bec7); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(0.6f);
            for (RectF cell : d.cells) canvas.drawRect(cell, paint);
        }
        for (OfficeTextLayout.Block block : d.texts) {
            canvas.save(); canvas.translate(block.x, block.y); block.layout.draw(canvas); canvas.restore();
        }
        canvas.restore();
    }
}
