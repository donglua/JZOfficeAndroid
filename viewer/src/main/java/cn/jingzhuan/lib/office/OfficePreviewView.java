package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class OfficePreviewView extends View {
    public interface Listener {
        void onLoaded(Info info);
        void onError(Exception error);
    }

    public static final class Info {
        public final String format;
        public final int pageCount;
        public final List<String> warnings;
        private Info(OfficeDocument document) {
            format = document.kind.name();
            pageCount = document.kind == OfficeDocument.Kind.PPTX ? document.pages.size() : 1;
            warnings = Collections.unmodifiableList(new ArrayList<>(document.warnings));
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private Future<?> pending;
    private int generation;
    private OfficeDocument document;
    private final List<DrawPage> pages = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private float zoom = 1, offsetX, offsetY, contentWidth, contentHeight;
    private String status = "";

    public OfficePreviewView(Context context) { this(context, null); }
    public OfficePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xffe9ecef);
        setContentDescription("Document preview");
        setFocusable(true);
        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (!pinch.isInProgress()) { offsetX += dx; offsetY += dy; clampOffsets(); invalidate(); }
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) { performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                setZoom(zoom > 1.1f ? 1 : 2, event.getX(), event.getY()); return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                scroller.fling((int) offsetX, (int) offsetY, (int) -vx, (int) -vy, 0, maxX(), 0, maxY());
                postInvalidateOnAnimation(); return true;
            }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                setZoom(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
    }

    public void open(Uri uri, Listener listener) {
        requireMainThread();
        if (uri == null || listener == null) throw new IllegalArgumentException("URI and listener are required");
        cancel();
        document = null; pages.clear(); zoom = 1; offsetX = offsetY = 0;
        contentWidth = contentHeight = 0;
        status = "Loading..."; invalidate();
        final int token = generation;
        final Context app = getContext().getApplicationContext();
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        pending = executor.submit(() -> {
            try {
                OfficeDocument loaded;
                try (OfficePackage pkg = OfficePackage.open(app, uri)) {
                    loaded = DocumentDecoder.decode(NativeCore.parse(pkg.file.getAbsolutePath()), pkg);
                }
                OfficePackage.checkCancelled();
                main.post(() -> {
                    if (token != generation) return;
                    document = loaded; status = ""; layoutDocument(); invalidate();
                    listener.onLoaded(new Info(loaded));
                });
            } catch (IOException | RuntimeException | LinkageError failure) {
                Exception error = failure instanceof Exception ? (Exception) failure : new IOException("Native core is unavailable for this device ABI", failure);
                main.post(() -> {
                    if (token != generation) return;
                    status = "Unable to open document"; invalidate(); listener.onError(error);
                });
            }
        });
    }

    public void clear() {
        requireMainThread(); cancel(); document = null; pages.clear();
        contentWidth = contentHeight = offsetX = offsetY = 0;
        status = ""; invalidate();
    }

    public void resetZoom() { requireMainThread(); setZoom(1, getWidth() / 2f, getHeight() / 2f); }

    public void setZoom(float value) { requireMainThread(); setZoom(value, getWidth() / 2f, getHeight() / 2f); }

    public float getZoom() { return zoom; }

    private void cancel() {
        generation++;
        if (pending != null) { pending.cancel(true); pending = null; }
        scroller.forceFinished(true);
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        if (executor != null) { executor.shutdownNow(); executor = null; }
        super.onDetachedFromWindow();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use the main thread");
    }

    private float baseScale() {
        return contentWidth > 0 ? Math.max(1, getWidth() - 24 * getResources().getDisplayMetrics().density) / contentWidth : 1;
    }
    private float scale() { return baseScale() * zoom; }
    private float left() { return Math.max(0, (getWidth() - contentWidth * scale()) / 2); }
    private float top() { return 12 * getResources().getDisplayMetrics().density; }
    private int maxX() { return Math.max(0, Math.round(contentWidth * scale() - getWidth())); }
    private int maxY() { return Math.max(0, Math.round(contentHeight * scale() + 2 * top() - getHeight())); }
    private void clampOffsets() { offsetX = Math.max(0, Math.min(maxX(), offsetX)); offsetY = Math.max(0, Math.min(maxY(), offsetY)); }
    private void setZoom(float value, float focusX, float focusY) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Zoom must be finite");
        scroller.forceFinished(true);
        float oldScale = scale();
        float x = (focusX + offsetX - left()) / oldScale;
        float y = (focusY + offsetY - top()) / oldScale;
        zoom = Math.max(1, Math.min(4, value));
        offsetX = x * scale() - focusX + left();
        offsetY = y * scale() - focusY + top();
        clampOffsets(); invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (document == null) return super.onTouchEvent(event);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        pinch.onTouchEvent(event); gestures.onTouchEvent(event);
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.getCurrX(); offsetY = scroller.getCurrY(); postInvalidateOnAnimation();
        }
    }
    @Override public boolean canScrollVertically(int direction) { return direction < 0 ? offsetY > 0 : offsetY < maxY(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { clampOffsets(); }

    private static final class TextBlock {
        StaticLayout layout;
        float x, y;
    }
    private static final class DrawElement {
        OfficeDocument.Element source;
        final List<TextBlock> texts = new ArrayList<>();
        final List<RectF> cells = new ArrayList<>();
        float x, y, width, height;
    }
    private static final class DrawPage {
        float y, width, height;
        int background;
        final List<DrawElement> elements = new ArrayList<>();
    }

    private void layoutDocument() {
        pages.clear(); contentHeight = 0;
        if (document == null) return;
        contentWidth = Math.max(1, document.width);
        if (document.kind == OfficeDocument.Kind.DOCX) {
            DrawPage page = new DrawPage(); page.width = contentWidth; page.background = 0xffffffff;
            float y = 32;
            for (OfficeDocument.Element element : document.blocks) {
                DrawElement drawn = layoutElement(element, contentWidth - 64, true);
                drawn.x = 32; drawn.y = y;
                if (element.type == OfficeDocument.Type.IMAGE) drawn.x += (contentWidth - 64 - drawn.width) / 2;
                page.elements.add(drawn); y += drawn.height + 6;
            }
            page.height = Math.max(300, y + 32); pages.add(page); contentHeight = page.height;
        } else {
            for (OfficeDocument.Page source : document.pages) {
                DrawPage page = new DrawPage(); page.y = contentHeight;
                page.width = source.width; page.height = source.height; page.background = source.background;
                for (OfficeDocument.Element e : source.elements) page.elements.add(layoutElement(e, e.width, false));
                pages.add(page); contentHeight += page.height + 16;
            }
            contentHeight = Math.max(0, contentHeight - 16);
        }
        clampOffsets();
    }

    private DrawElement layoutElement(OfficeDocument.Element e, float available, boolean flow) {
        DrawElement d = new DrawElement(); d.source = e;
        d.x = e.x; d.y = e.y;
        d.width = Math.max(1, flow ? (e.type == OfficeDocument.Type.TABLE && e.width > 0 ? Math.min(e.width, available) : available) : e.width);
        d.height = Math.max(1, e.height);
        if (e.type == OfficeDocument.Type.IMAGE && flow) {
            float width = e.width > 0 ? e.width : e.image != null ? e.image.getWidth() : available;
            float height = e.height > 0 ? e.height : e.image != null ? e.image.getHeight() : 100;
            d.width = Math.min(available, width); d.height = height * d.width / Math.max(1, width);
        } else if (e.type == OfficeDocument.Type.TABLE) {
            int cols = 0;
            for (List<List<OfficeDocument.Paragraph>> row : e.rows) cols = Math.max(cols, row.size());
            float total = 0;
            for (Float width : e.columnWidths) total += Math.max(1, width);
            float y = 0;
            for (List<List<OfficeDocument.Paragraph>> row : e.rows) {
                float x = 0, rowHeight = 20;
                int first = d.cells.size();
                for (int c = 0; c < cols; c++) {
                    float width = e.columnWidths.size() == cols && total > 0 ? d.width * Math.max(1, e.columnWidths.get(c)) / total : d.width / Math.max(1, cols);
                    List<OfficeDocument.Paragraph> cell = c < row.size() ? row.get(c) : Collections.emptyList();
                    rowHeight = Math.max(rowHeight, layoutParagraphs(cell, x + 5, y + 5, Math.max(1, width - 10), d.texts) - y + 5);
                    d.cells.add(new RectF(x, y, x + width, y)); x += width;
                }
                for (int i = first; i < d.cells.size(); i++) d.cells.get(i).bottom = y + rowHeight;
                y += rowHeight;
            }
            if (flow) d.height = Math.max(1, y);
            else if (y > d.height) document.warn("Table content exceeds its slide bounds and is clipped");
        } else if (!e.paragraphs.isEmpty()) {
            float padding = flow ? 0 : Math.max(0, e.padding);
            float height = layoutParagraphs(e.paragraphs, padding, padding, Math.max(1, d.width - 2 * padding), d.texts) + padding;
            if (flow) d.height = Math.max(1, height);
            else if (height > d.height + 1) document.warn("Text exceeds its slide box and is clipped");
        }
        return d;
    }

    private static float layoutParagraphs(List<OfficeDocument.Paragraph> paragraphs, float x, float y, float width, List<TextBlock> out) {
        for (OfficeDocument.Paragraph paragraph : paragraphs) {
            y += Math.max(0, paragraph.before);
            SpannableStringBuilder text = new SpannableStringBuilder();
            text.append(paragraph.bullet);
            for (OfficeDocument.Run run : paragraph.runs) {
                int start = text.length(); text.append(run.text);
                if (start == text.length()) continue;
                int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
                text.setSpan(new AbsoluteSizeSpan(Math.max(1, Math.round(run.size))), start, text.length(), flags);
                text.setSpan(new ForegroundColorSpan(run.color), start, text.length(), flags);
                int style = (run.bold ? Typeface.BOLD : 0) | (run.italic ? Typeface.ITALIC : 0);
                if (style != 0) text.setSpan(new StyleSpan(style), start, text.length(), flags);
                if (run.underline) text.setSpan(new UnderlineSpan(), start, text.length(), flags);
            }
            if (text.length() == 0) text.append(" ");
            TextPaint font = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            font.setTextSize(paragraph.runs.isEmpty() ? 12 : paragraph.runs.get(0).size);
            font.setColor(0xff202124);
            Layout.Alignment align = paragraph.alignment == 1 ? Layout.Alignment.ALIGN_CENTER : paragraph.alignment == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            float indent = Math.max(0, Math.min(width - 1, paragraph.indent));
            TextBlock block = new TextBlock(); block.x = x + indent; block.y = y;
            block.layout = StaticLayout.Builder.obtain(text, 0, text.length(), font, Math.max(1, (int) (width - indent)))
                .setAlignment(align).setIncludePad(false).setLineSpacing(1, 1).build();
            out.add(block); y += block.layout.getHeight() + Math.max(0, paragraph.after);
        }
        return y;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (document == null) {
            paint.setColor(0xff656b73); paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(status, Math.max(16, (getWidth() - paint.measureText(status)) / 2), getHeight() / 2f, paint);
            return;
        }
        canvas.save(); canvas.translate(left() - offsetX, top() - offsetY); canvas.scale(scale(), scale());
        float visibleTop = (offsetY - top()) / scale(), visibleBottom = visibleTop + getHeight() / scale();
        for (DrawPage page : pages) {
            if (page.y + page.height < visibleTop || page.y > visibleBottom) continue;
            canvas.save(); canvas.translate(0, page.y); canvas.clipRect(0, 0, page.width, page.height);
            paint.setStyle(Paint.Style.FILL); paint.setColor(page.background); canvas.drawRect(0, 0, page.width, page.height, paint);
            for (DrawElement e : page.elements) {
                if (e.source.rotation != 0 || (e.y + e.height >= visibleTop - page.y && e.y <= visibleBottom - page.y)) drawElement(canvas, e);
            }
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawElement(Canvas canvas, DrawElement d) {
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
        canvas.clipRect(rect);
        paint.setStyle(Paint.Style.FILL);
        if (e.type == OfficeDocument.Type.IMAGE) {
            if (e.image != null) { paint.setColor(0xffffffff); canvas.drawBitmap(e.image, null, rect, paint); }
            else { paint.setColor(0xffe0e0e0); canvas.drawRect(rect, paint); }
        }
        if (e.type == OfficeDocument.Type.TABLE) {
            paint.setColor(0xffb7bec7); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(0.6f);
            for (RectF cell : d.cells) canvas.drawRect(cell, paint);
        }
        for (TextBlock block : d.texts) {
            canvas.save(); canvas.translate(block.x, block.y); block.layout.draw(canvas); canvas.restore();
        }
        canvas.restore();
    }
}
