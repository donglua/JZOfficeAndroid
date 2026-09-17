use super::{package::Parts, pptx};

pub fn parts() -> Parts {
    let mut parts = pptx::parts();
    for (name, bytes) in &mut parts {
        if *name == "ppt/slides/slide2.xml" {
            *bytes = slide().into_bytes();
        }
    }
    parts
}

fn slide() -> String {
    format!(r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></p:bgPr></p:bg><p:spTree>
<p:grpSp><p:nvGrpSpPr><p:cNvPr id="10" name="Scaled group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="1270000" y="635000"/><a:ext cx="2540000" cy="1270000"/><a:chOff x="127000" y="254000"/><a:chExt cx="1270000" cy="1270000"/></a:xfrm></p:grpSpPr>
<p:sp><p:nvSpPr><p:cNvPr id="11" name="Grouped rectangle"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="254000" y="381000"/><a:ext cx="254000" cy="254000"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="22AA66"/></a:solidFill></p:spPr></p:sp>
<p:grpSp><p:nvGrpSpPr><p:cNvPr id="12" name="Nested group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="254000" y="762000"/><a:ext cx="1016000" cy="381000"/><a:chOff x="0" y="0"/><a:chExt cx="1016000" cy="381000"/></a:xfrm></p:grpSpPr>
{} </p:grpSp>
<p:pic><p:nvPicPr><p:cNvPr id="14" name="Grouped flipped picture"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm flipH="1"><a:off x="762000" y="381000"/><a:ext cx="508000" cy="254000"/></a:xfrm></p:spPr></p:pic></p:grpSp>
<p:grpSp><p:nvGrpSpPr><p:cNvPr id="20" name="Rotated group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm rot="5400000"><a:off x="5080000" y="635000"/><a:ext cx="1270000" cy="762000"/><a:chOff x="0" y="0"/><a:chExt cx="1270000" cy="762000"/></a:xfrm></p:grpSpPr>
<p:sp><p:nvSpPr><p:cNvPr id="21" name="Rotated bar"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1016000" cy="254000"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="E47722"/></a:solidFill></p:spPr></p:sp></p:grpSp>
{}{}
</p:spTree></p:cSld></p:sld>"#,
        text(13, [0, 0, 1_016_000, 381_000], "", "", "Grouped &#x4E2D;&#x6587;", 1200),
        text(30, [457_200, 2_286_000, 7_620_000, 2_286_000],
            r#"<a:normAutofit fontScale="75000" lnSpcReduction="20000"/>"#,
            r#"marL="304800" marR="152400" indent="-152400""#,
            "PPTX line spacing and hanging indent: &#x4E2D;&#x6587;&#x6392;&#x7248; with saved autofit. This text wraps onto multiple lines so spacing and indents remain visible across zoom levels.", 2400),
        coordinate_group())
}

pub fn coordinate_group() -> String {
    format!(
        r#"<p:grpSp><p:grpSpPr><a:xfrm><a:off x="508000" y="4572000"/><a:ext cx="8128000" cy="508000"/><a:chOff x="0" y="0"/><a:chExt cx="12800" cy="800"/></a:xfrm></p:grpSpPr>
<p:sp><p:spPr><a:xfrm><a:off x="11200" y="100"/><a:ext cx="1600" cy="500"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="003AAC"/></a:solidFill><a:ln w="25400"><a:solidFill><a:srgbClr val="003F68"/></a:solidFill></a:ln></p:spPr></p:sp>
{}</p:grpSp>"#,
        text(41, [0, 0, 10000, 800], "", "", "Coordinate units", 2400)
            .replace("lIns=\"0\" rIns=\"0\" tIns=\"0\" bIns=\"0\"", "")
    )
}

fn text(id: u32, rect: [u32; 4], autofit: &str, paragraph: &str, value: &str, size: u32) -> String {
    let [x, y, width, height] = rect;
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Text {id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm><a:noFill/></p:spPr><p:txBody><a:bodyPr lIns="0" rIns="0" tIns="0" bIns="0">{autofit}</a:bodyPr><a:lstStyle/><a:p><a:pPr {paragraph}><a:lnSpc><a:spcPct val="150000"/></a:lnSpc></a:pPr><a:r><a:rPr sz="{size}"><a:solidFill><a:srgbClr val="222222"/></a:solidFill></a:rPr><a:t>{value}</a:t></a:r></a:p></p:txBody></p:sp>"#
    )
}
