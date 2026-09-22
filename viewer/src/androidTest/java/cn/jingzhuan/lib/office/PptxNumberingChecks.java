package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;

import android.content.Context;
import android.net.Uri;
import java.io.File;

final class PptxNumberingChecks {
    private PptxNumberingChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        String[][] cases = {
            {"arabicPeriod", "4. ", "5. "}, {"arabicParenR", "4) ", "5) "},
            {"arabicParenBoth", "(4) ", "(5) "}, {"arabicPlain", "4 ", "5 "},
            {"alphaLcPeriod", "d. ", "e. "}, {"alphaUcPeriod", "D. ", "E. "},
            {"alphaLcParenR", "d) ", "e) "}, {"alphaUcParenR", "D) ", "E) "},
            {"alphaLcParenBoth", "(d) ", "(e) "}, {"alphaUcParenBoth", "(D) ", "(E) "},
            {"romanLcPeriod", "iv. ", "v. "}, {"romanUcPeriod", "IV. ", "V. "},
            {"romanLcParenR", "iv) ", "v) "}, {"romanUcParenR", "IV) ", "V) "},
            {"romanLcParenBoth", "(iv) ", "(v) "}, {"romanUcParenBoth", "(IV) ", "(V) "}
        };
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < cases.length; i++) {
            int x = i < 8 ? 20 : 370, y = 16 + i % 8 * 47;
            shapes.append("<p:sp><p:spPr><a:xfrm><a:off x=\"").append(x * 12700)
                .append("\" y=\"").append(y * 12700).append("\"/><a:ext cx=\"4191000\" cy=\"558800\"/></a:xfrm>")
                .append("<a:noFill/></p:spPr><p:txBody><a:bodyPr lIns=\"0\" rIns=\"0\" tIns=\"0\" bIns=\"0\"/>");
            for (int n = 0; n < 2; n++) shapes.append("<a:p><a:pPr><a:buAutoNum type=\"").append(cases[i][0])
                .append("\" startAt=\"4\"/></a:pPr><a:r><a:rPr sz=\"1200\"/><a:t>")
                .append(n == 0 ? cases[i][0] : "Next 下一项").append("</a:t></a:r></a:p>");
            shapes.append("</p:txBody></p:sp>");
        }
        Context context = instrumentation.getTargetContext();
        File source = CompatibilityChecks.presentation(context, "numbering", shapes.toString());
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(source))) {
            OfficeDocument document = pkg.document;
            check(document.warnings.isEmpty(), "All supported numbering schemes avoid fallback warnings");
            check(document.pages.get(0).elements.size() == cases.length, "Numbering fixture has 16 text boxes");
            instrumentation.runOnMainChecked(() -> {
                for (int i = 0; i < cases.length; i++) {
                    OfficeDocument.Element box = document.pages.get(0).elements.get(i);
                    for (int n = 0; n < 2; n++) {
                        OfficeDocument.Paragraph paragraph = box.paragraphs.get(n);
                        check(cases[i][n + 1].equals(paragraph.bullet), "Parsed numbering " + cases[i][0]);
                        check(OfficeTextLayout.paragraph(paragraph, box.width).getText().toString().startsWith(cases[i][n + 1]),
                            "Numbering reaches StaticLayout " + cases[i][0]);
                    }
                }
            });
            CompatibilityChecks.show(instrumentation, activity, source, "compat-numbering-screen.png");
        } finally { check(source.delete(), "Delete numbering fixture"); }
        return "PASS all 16 PPTX numbering formats through JNI, StaticLayout and URI preview\n";
    }
}
