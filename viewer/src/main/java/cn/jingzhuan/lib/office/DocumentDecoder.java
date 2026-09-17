package cn.jingzhuan.lib.office;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DocumentDecoder {
    static OfficeDocument decode(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            if (root.getInt("schemaVersion") != 2) throw new IOException("Unsupported core model version");
            OfficeDocument document = new OfficeDocument();
            document.kind = OfficeDocument.Kind.valueOf(root.getString("kind"));
            document.width = (float) root.getDouble("width");
            JSONArray warnings = root.getJSONArray("warnings");
            for (int i = 0; i < warnings.length(); i++) document.warn(warnings.getString(i));
            JSONArray blocks = root.getJSONArray("blocks");
            for (int i = 0; i < blocks.length(); i++) document.blocks.add(element(blocks.getJSONObject(i)));
            JSONArray pages = root.getJSONArray("pages");
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = pages.getJSONObject(i);
                OfficeDocument.Page page = new OfficeDocument.Page();
                page.width = (float) p.getDouble("width"); page.height = (float) p.getDouble("height");
                page.background = (int) p.getLong("background");
                JSONArray elements = p.getJSONArray("elements");
                for (int j = 0; j < elements.length(); j++) page.elements.add(element(elements.getJSONObject(j)));
                document.pages.add(page);
            }
            return document;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Invalid core display model", e); }
    }

    private static OfficeDocument.Element element(JSONObject json) throws JSONException {
        OfficeDocument.Element e = new OfficeDocument.Element();
        e.type = OfficeDocument.Type.valueOf(json.getString("type"));
        e.x = (float) json.getDouble("x"); e.y = (float) json.getDouble("y");
        e.width = (float) json.getDouble("width"); e.height = (float) json.getDouble("height");
        e.rotation = (float) json.getDouble("rotation"); e.padding = (float) json.getDouble("padding");
        e.fill = (int) json.getLong("fill"); e.stroke = (int) json.getLong("stroke");
        e.strokeWidth = (float) json.getDouble("strokeWidth");
        e.verticalAlignment = OfficeDocument.VerticalAlignment.valueOf(json.getString("verticalAlignment"));
        JSONObject crop = json.optJSONObject("imageCrop");
        if (crop != null) {
            e.imageCrop = new OfficeDocument.ImageCrop();
            e.imageCrop.left = (float) crop.getDouble("left");
            e.imageCrop.top = (float) crop.getDouble("top");
            e.imageCrop.right = (float) crop.getDouble("right");
            e.imageCrop.bottom = (float) crop.getDouble("bottom");
        }
        if (!json.isNull("image")) {
            e.image = json.getString("image");
        }
        e.paragraphs.addAll(paragraphs(json.getJSONArray("paragraphs")));
        JSONArray widths = json.getJSONArray("columnWidths");
        for (int i = 0; i < widths.length(); i++) e.columnWidths.add((float) widths.getDouble(i));
        JSONArray rows = json.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            List<List<OfficeDocument.Paragraph>> row = new ArrayList<>();
            JSONArray cells = rows.getJSONArray(i);
            for (int j = 0; j < cells.length(); j++) row.add(paragraphs(cells.getJSONArray(j)));
            e.rows.add(row);
        }
        return e;
    }

    private static List<OfficeDocument.Paragraph> paragraphs(JSONArray array) throws JSONException {
        List<OfficeDocument.Paragraph> paragraphs = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject p = array.getJSONObject(i);
            OfficeDocument.Paragraph paragraph = new OfficeDocument.Paragraph();
            paragraph.alignment = p.getInt("alignment"); paragraph.before = (float) p.getDouble("before");
            paragraph.after = (float) p.getDouble("after"); paragraph.indent = (float) p.getDouble("indent");
            paragraph.rightIndent = (float) p.getDouble("rightIndent");
            paragraph.firstLineIndent = (float) p.getDouble("firstLineIndent");
            paragraph.lineSpacingRule = OfficeDocument.LineSpacingRule.valueOf(p.getString("lineSpacingRule"));
            paragraph.lineSpacing = (float) p.getDouble("lineSpacing");
            paragraph.bullet = p.getString("bullet");
            JSONArray runs = p.getJSONArray("runs");
            for (int j = 0; j < runs.length(); j++) {
                JSONObject r = runs.getJSONObject(j);
                OfficeDocument.Run run = new OfficeDocument.Run();
                run.text = r.getString("text"); run.size = (float) r.getDouble("size");
                run.bold = r.getBoolean("bold"); run.italic = r.getBoolean("italic");
                run.underline = r.getBoolean("underline"); run.color = (int) r.getLong("color");
                paragraph.runs.add(run);
            }
            paragraphs.add(paragraph);
        }
        return paragraphs;
    }
}
