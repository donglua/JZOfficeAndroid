pub const GROUP: &str = r#"<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"#;

pub fn title(text: &str, color: &str) -> String {
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="304800"/><a:ext cx="6350000" cy="685800"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>
<p:txBody><a:bodyPr lIns="0" tIns="0" rIns="0" bIns="0"/><a:lstStyle/>
<a:p><a:pPr><a:defRPr sz="2800" b="1"/></a:pPr><a:r><a:rPr lang="en-US" sz="2800" b="1">
<a:solidFill><a:srgbClr val="{color}"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r>
<a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>"#
    )
}

pub const GEOMETRY: &str = r#"<p:sp><p:nvSpPr><p:cNvPr id="3" name="Rectangle"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="1422400"/><a:ext cx="2286000" cy="762000"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="DCFCE7"/></a:solidFill>
<a:ln w="25400"><a:solidFill><a:srgbClr val="228855"/></a:solidFill></a:ln></p:spPr></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="4" name="Ellipse"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="3048000" y="1422400"/><a:ext cx="1016000" cy="635000"/></a:xfrm>
<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFE3A3"/></a:solidFill>
<a:ln w="12700"><a:solidFill><a:srgbClr val="9A6500"/></a:solidFill></a:ln></p:spPr></p:sp>
<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="5" name="Horizontal line"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="2413000"/><a:ext cx="3606800" cy="0"/></a:xfrm>
<a:prstGeom prst="line"><a:avLst/></a:prstGeom>
<a:ln w="25400"><a:solidFill><a:srgbClr val="475569"/></a:solidFill></a:ln></p:spPr></p:cxnSp>"#;

pub const IMAGE: &str = r#"<p:pic><p:nvPicPr><p:cNvPr id="6" name="Small teal PNG"/>
<p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>
<p:blipFill><a:blip r:embed="rImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
<p:spPr><a:xfrm><a:off x="5080000" y="1422400"/><a:ext cx="914400" cy="457200"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"#;

pub fn table() -> String {
    let mut text = String::from(
        r#"<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="7" name="Bilingual table"/>
<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
<p:xfrm><a:off x="457200" y="2921000"/><a:ext cx="4572000" cy="914400"/></p:xfrm>
<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">
<a:tbl><a:tblPr/><a:tblGrid><a:gridCol w="1828800"/><a:gridCol w="2743200"/></a:tblGrid>"#,
    );
    for row in [
        ["Language", "Greeting"],
        ["&#x4E2D;&#x6587;", "Hello &#x4F60;&#x597D;"],
    ] {
        text.push_str(r#"<a:tr h="457200">"#);
        for cell in row {
            text.push_str(&format!(
                r#"<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>
<a:p><a:r><a:rPr lang="en-US" sz="1600"/><a:t>{cell}</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>"#
            ));
        }
        text.push_str("</a:tr>");
    }
    text.push_str("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>");
    text
}
