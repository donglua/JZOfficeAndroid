package cn.jingzhuan.lib.office;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import java.util.List;

final class OfficeTextLayout {
    static final class Block {
        StaticLayout layout;
        float x, y;
    }

    static float append(List<OfficeDocument.Paragraph> paragraphs, float x, float y, float width, List<Block> out) {
        for (OfficeDocument.Paragraph paragraph : paragraphs) {
            y += Math.max(0, paragraph.before);
            Block block = new Block();
            block.x = x; block.y = y;
            block.layout = paragraph(paragraph, width);
            out.add(block);
            y += block.layout.getHeight() + Math.max(0, paragraph.after);
        }
        return y;
    }

    static StaticLayout paragraph(OfficeDocument.Paragraph paragraph, float width) {
        SpannableStringBuilder text = new SpannableStringBuilder(paragraph.bullet);
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
        Layout.Alignment align = paragraph.alignment == 1 ? Layout.Alignment.ALIGN_CENTER
            : paragraph.alignment == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        int layoutWidth = Math.max(1, (int) width);
        int right = Math.max(0, Math.min(layoutWidth - 1, Math.round(paragraph.rightIndent)));
        int rest = Math.max(0, Math.min(layoutWidth - right - 1, Math.round(paragraph.indent)));
        int first = Math.max(0, Math.min(layoutWidth - right - 1, Math.round(paragraph.indent + paragraph.firstLineIndent)));
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), font, layoutWidth)
            .setAlignment(align).setIncludePad(false)
            .setIndents(new int[] {first, rest}, new int[] {right});
        if (paragraph.lineSpacingRule == OfficeDocument.LineSpacingRule.AUTO) {
            builder.setLineSpacing(0, paragraph.lineSpacing);
        } else {
            text.setSpan(new FixedLineHeight(paragraph.lineSpacing,
                paragraph.lineSpacingRule == OfficeDocument.LineSpacingRule.EXACT),
                0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder.build();
    }

    private static final class FixedLineHeight implements LineHeightSpan {
        private final int height;
        private final boolean exact;

        FixedLineHeight(float height, boolean exact) {
            this.height = Math.max(1, Math.round(height)); this.exact = exact;
        }

        @Override public void chooseHeight(CharSequence text, int start, int end, int spanStartY, int lineTop, Paint.FontMetricsInt metrics) {
            int natural = Math.max(1, metrics.descent - metrics.ascent);
            if (!exact && natural >= height) return;
            int descent = Math.round(metrics.descent * (float) height / natural);
            metrics.descent = metrics.bottom = descent;
            metrics.ascent = metrics.top = descent - height;
        }
    }
}
