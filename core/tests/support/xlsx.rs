use super::package::{content_types, relationships, xml, Parts};

pub const WORKBOOK: &str = r#"<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr date1904="0"/><sheets><sheet name="Overview" sheetId="2" r:id="second"/><sheet name="Data" sheetId="1" r:id="first"/></sheets></workbook>"#;

pub const STYLES: &str = r#"<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/></numFmts>
<fonts count="2"><font><sz val="11"/><color rgb="FF202124"/></font><font><sz val="20"/><b/><i/><u/><color rgb="FFFFFFFF"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF24745C"/></patternFill></fill></fills>
<borders count="2"><border/><border><left style="thin"><color rgb="FF112233"/></left><top style="thin"><color rgb="FF112233"/></top><right style="thin"><color rgb="FF112233"/></right><bottom style="thin"><color rgb="FF112233"/></bottom></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="4" fontId="0" fillId="0" borderId="0"/><xf numFmtId="10" fontId="0" fillId="0" borderId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0"/><xf numFmtId="21" fontId="0" fillId="0" borderId="0"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0"/></cellXfs></styleSheet>"#;

pub fn sheet(body: &str) -> String {
    format!(
        r#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">{body}</worksheet>"#
    )
}

pub fn parts() -> Parts {
    let mut rows = String::from(
        r#"<row r="1" ht="36"><c r="A1" s="1" t="s"><v>0</v></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>Amount</t></is></c><c r="B2" s="2"><v>1234.5</v></c></row>
<row r="3"><c r="A3" t="inlineStr"><is><t>Percent</t></is></c><c r="B3" s="3"><v>0.125</v></c></row>
<row r="4"><c r="A4" t="inlineStr"><is><t>Date</t></is></c><c r="B4" s="4"><v>45292</v></c></row>
<row r="5"><c r="A5" t="inlineStr"><is><t>Cached formula</t></is></c><c r="B5" s="2"><f>B2*2</f><v>2469</v></c></row>
<row r="6"><c r="A6" t="inlineStr"><is><t>Boolean</t></is></c><c r="B6" t="b"><v>1</v></c></row>
<row r="7"><c r="A7" t="inlineStr"><is><t>Error</t></is></c><c r="B7" t="e"><v>#DIV/0!</v></c></row>
<row r="8"><c r="A8" t="inlineStr"><is><r><t>Rich </t></r><r><t>text</t></r></is></c><c r="B8" s="5"><v>0.5</v></c></row>
<row r="9"><c r="A9" t="inlineStr"><is><t>Uncached formula</t></is></c><c r="B9"><f>SUM(B2:B3)</f></c></row>"#,
    );
    for row in 10..=120 {
        rows.push_str(&format!(r#"<row r="{row}">"#));
        for column in b'A'..=b'L' {
            let column = char::from(column);
            rows.push_str(&format!(
                r#"<c r="{column}{row}" t="inlineStr"><is><t>{column}{row}</t></is></c>"#
            ));
        }
        rows.push_str("</row>");
    }
    vec![
        xml(
            "[Content_Types].xml",
            &content_types(&[
                ("xl/workbook.xml", "spreadsheetml.sheet.main"),
                ("xl/worksheets/sheet1.xml", "spreadsheetml.worksheet"),
                ("xl/worksheets/sheet2.xml", "spreadsheetml.worksheet"),
                ("xl/styles.xml", "spreadsheetml.styles"),
                ("xl/sharedStrings.xml", "spreadsheetml.sharedStrings"),
            ]),
        ),
        xml(
            "_rels/.rels",
            &relationships(&[("main", "officeDocument", "xl/workbook.xml")]),
        ),
        xml("xl/workbook.xml", WORKBOOK),
        xml(
            "xl/_rels/workbook.xml.rels",
            &relationships(&[
                ("first", "worksheet", "worksheets/sheet1.xml"),
                ("second", "worksheet", "worksheets/sheet2.xml"),
                ("styles", "styles", "styles.xml"),
                ("strings", "sharedStrings", "sharedStrings.xml"),
            ]),
        ),
        xml("xl/styles.xml", STYLES),
        xml(
            "xl/sharedStrings.xml",
            r#"<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><r><t>Quarterly </t></r><r><t>Overview</t></r><rPh sb="0" eb="1"><t>phonetic</t></rPh></si></sst>"#,
        ),
        xml(
            "xl/worksheets/sheet2.xml",
            &sheet(&format!(
                r#"<sheetFormatPr defaultRowHeight="18" defaultColWidth="12"/><cols><col min="1" max="1" width="24"/><col min="2" max="12" width="16"/></cols><sheetData>{rows}</sheetData><mergeCells><mergeCell ref="A1:D1"/></mergeCells>"#
            )),
        ),
        xml(
            "xl/worksheets/sheet1.xml",
            &sheet(
                r#"<sheetData><row r="1" ht="36"><c r="A1" s="1" t="inlineStr"><is><t>Data sheet</t></is></c></row><row r="2"><c r="A2"><v>42</v></c><c r="B2" t="str"><f>"ready"</f><v>ready</v></c></row></sheetData><mergeCells><mergeCell ref="A1:D1"/></mergeCells>"#,
            ),
        ),
    ]
}
