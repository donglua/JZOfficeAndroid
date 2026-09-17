package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LayoutFixtures {
    private LayoutFixtures() { }

    static File docx(File dir) throws IOException {
        File file = File.createTempFile("layout-render-", ".docx", dir);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            xml(zip, "[Content_Types].xml", contentTypes(new String[][] {
                {"word/document.xml", "wordprocessingml.document.main"},
                {"word/styles.xml", "wordprocessingml.styles"}
            }));
            xml(zip, "_rels/.rels", rels(new String[][] {{"rOffice", "officeDocument", "word/document.xml"}}));
            xml(zip, "word/_rels/document.xml.rels", rels(new String[][] {{"rStyles", "styles", "styles.xml"}}));
            xml(zip, "word/styles.xml", STYLES);
            xml(zip, "word/document.xml", DOCX_DOCUMENT);
        }
        return file;
    }

    static File pptx(File dir) throws IOException {
        File file = File.createTempFile("layout-render-", ".pptx", dir);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            xml(zip, "[Content_Types].xml", contentTypes(new String[][] {
                {"ppt/presentation.xml", "presentationml.presentation.main"},
                {"ppt/slides/slide1.xml", "presentationml.slide"},
                {"ppt/slides/slide2.xml", "presentationml.slide"}
            }));
            xml(zip, "_rels/.rels", rels(new String[][] {{"rOffice", "officeDocument", "ppt/presentation.xml"}}));
            xml(zip, "ppt/presentation.xml", PRESENTATION);
            xml(zip, "ppt/_rels/presentation.xml.rels", rels(new String[][] {
                {"rSlide1", "slide", "slides/slide1.xml"},
                {"rSlide2", "slide", "slides/slide2.xml"}
            }));
            xml(zip, "ppt/slides/_rels/slide1.xml.rels", rels(new String[][] {{"rImage", "image", "../media/quadrants.png"}}));
            xml(zip, "ppt/slides/_rels/slide2.xml.rels", rels(new String[0][]));
            xml(zip, "ppt/slides/slide1.xml", slide("FFFFFF", verticalBoxes() + images()));
            xml(zip, "ppt/slides/slide2.xml", slide("EAF4FF", textBox(20, "t", 686, 914, 1800, 686, "Second slide marker")));
            bytes(zip, "ppt/media/quadrants.png", quadrantPng());
        }
        return file;
    }

    private static final String STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"32\"/><w:color w:val=\"202124\"/></w:rPr></w:rPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
        + "</w:styles>";

    private static final String LONG =
        "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda multilingual 中文 English layout wrap ";

    private static final String DOCX_DOCUMENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + p("auto one " + LONG + LONG, "<w:spacing w:line=\"240\" w:lineRule=\"auto\"/>")
        + p("auto one point five " + LONG + LONG, "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>")
        + p("auto two " + LONG + LONG, "<w:spacing w:line=\"480\" w:lineRule=\"auto\"/>")
        + p("exact line " + LONG + LONG, "<w:spacing w:line=\"600\" w:lineRule=\"exact\"/>")
        + p("at least line " + LONG + LONG, "<w:spacing w:line=\"560\" w:lineRule=\"atLeast\"/>")
        + p("first indent " + LONG + LONG, "<w:ind w:left=\"480\" w:right=\"240\" w:firstLine=\"200\"/>")
        + p("hanging indent " + LONG + LONG, "<w:ind w:left=\"480\" w:right=\"240\" w:hanging=\"160\"/>")
        + "<w:sectPr><w:pgSz w:w=\"7200\" w:h=\"12000\"/></w:sectPr></w:body></w:document>";

    private static String p(String text, String properties) {
        return "<w:p><w:pPr>" + properties + "</w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static final String PRESENTATION =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rSlide1\"/><p:sldId id=\"257\" r:id=\"rSlide2\"/></p:sldIdLst>"
        + "<p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>"
        + "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\" sz=\"1600\"/></a:defPPr></p:defaultTextStyle></p:presentation>";

    private static String slide(String background, String content) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"" + background + "\"/></a:solidFill></p:bgPr></p:bg>"
            + "<p:spTree>" + GROUP + content + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }

    private static final String GROUP =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
        + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
        + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>";

    private static String verticalBoxes() {
        return textBox(2, "t", 457, 457, 2057, 914, "Vertical anchor")
            + textBox(3, "ctr", 2971, 457, 2057, 914, "Vertical anchor")
            + textBox(4, "b", 5486, 457, 2057, 914, "Vertical anchor");
    }

    private static String textBox(int id, String anchor, int x, int y, int w, int h, String text) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"" + text + "\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
            + "<p:spPr><a:xfrm><a:off x=\"" + x + "200\" y=\"" + y + "200\"/><a:ext cx=\"" + w + "200\" cy=\"" + h + "200\"/></a:xfrm>"
            + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>"
            + "<p:txBody><a:bodyPr anchor=\"" + anchor + "\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/><a:lstStyle/>"
            + "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1600\"><a:solidFill><a:srgbClr val=\"111111\"/></a:solidFill></a:rPr>"
            + "<a:t>" + text + "</a:t></a:r></a:p></p:txBody></p:sp>";
    }

    private static String images() {
        return image(5, 457, 2057, false) + image(6, 2971, 2057, true);
    }

    private static String image(int id, int x, int y, boolean cropped) {
        String crop = cropped ? "<a:srcRect l=\"50000\" b=\"50000\"/>" : "";
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"" + id + "\" name=\"Quadrants\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
            + "<p:blipFill><a:blip r:embed=\"rImage\"/>" + crop + "<a:stretch><a:fillRect/></a:stretch></p:blipFill>"
            + "<p:spPr><a:xfrm><a:off x=\"" + x + "200\" y=\"" + y + "200\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
            + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
    }

    private static void xml(ZipOutputStream zip, String name, String text) throws IOException {
        bytes(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String rels(String[][] entries) {
        StringBuilder text = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (String[] e : entries) text.append("<Relationship Id=\"").append(e[0]).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/").append(e[1]).append("\" Target=\"").append(e[2]).append("\"/>");
        return text.append("</Relationships>").toString();
    }

    private static String contentTypes(String[][] overrides) {
        StringBuilder text = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>");
        for (String[] o : overrides) text.append("<Override PartName=\"/").append(o[0]).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.").append(o[1]).append("+xml\"/>");
        return text.append("</Types>").toString();
    }

    private static byte[] quadrantPng() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) {
            bitmap.setPixel(x, y, y < 20 ? (x < 20 ? Color.RED : Color.GREEN) : (x < 20 ? Color.BLUE : Color.YELLOW));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }
}
