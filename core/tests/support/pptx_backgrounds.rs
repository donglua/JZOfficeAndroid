use super::{
    package::{relationships, xml, Parts},
    pptx,
    pptx_shapes::GROUP,
};

const GRADIENT: &str = r#"<p:bg><p:bgPr><a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FF6A28"/></a:gs><a:gs pos="100000"><a:srgbClr val="F43F12"/></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill></p:bgPr></p:bg>"#;
// Generated 40x40 RGBA image: transparent except for a white bar at x=4..35, y=6..13.
const TRANSPARENT_TITLE: &[u8] = &[
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x28, 0x08, 0x06, 0x00, 0x00, 0x00, 0x8c, 0xfe, 0xb8,
    0x6d, 0x00, 0x00, 0x00, 0x28, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0xed, 0xce, 0x31, 0x0d, 0x00,
    0x00, 0x08, 0x03, 0xb0, 0xf9, 0x37, 0x0d, 0x16, 0xf8, 0x96, 0x90, 0x56, 0x41, 0x13, 0xe0, 0x99,
    0x29, 0x13, 0x14, 0x14, 0x14, 0x6c, 0x07, 0x01, 0x00, 0x00, 0x00, 0xce, 0x16, 0x09, 0x07, 0xfc,
    0x2e, 0xe2, 0x9f, 0x82, 0x90, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60,
    0x82,
];

pub fn parts() -> Parts {
    let mut parts = pptx::parts();
    parts.retain(|(path, _)| *path != "ppt/media/pixel.png");
    for (path, bytes) in &mut parts {
        match *path {
            "[Content_Types].xml" => {
                let extra = r#"<Override PartName="/ppt/slides/slide3.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"#;
                bytes.splice(bytes.len() - "</Types>".len()..bytes.len() - "</Types>".len(), extra.bytes());
            }
            "ppt/presentation.xml" => *bytes = pptx::PRESENTATION
                .replace(r#"<p:sldId id="257" r:id="rSecond"/><p:sldId id="256" r:id="rFirst"/>"#,
                    r#"<p:sldId id="256" r:id="rFirst"/><p:sldId id="257" r:id="rSecond"/><p:sldId id="258" r:id="rThird"/>"#)
                .replace("9144000", "4064000").replace("5143500", "2286000").into_bytes(),
            "ppt/_rels/presentation.xml.rels" => *bytes = relationships(&[
                ("rFirst", "slide", "slides/slide1.xml"),
                ("rSecond", "slide", "slides/slide2.xml"),
                ("rThird", "slide", "slides/slide3.xml"),
                ("rMaster", "slideMaster", "slideMasters/slideMaster1.xml"),
            ]).into_bytes(),
            "ppt/slides/slide1.xml" => *bytes = slide("", &format!(r#"<p:pic>
<p:nvPicPr><p:cNvPr id="2" name="Transparent title"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
<p:blipFill><a:blip r:embed="rImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
<p:spPr><a:xfrm><a:off x="1778000" y="889000"/><a:ext cx="508000" cy="508000"/></a:xfrm><a:prstGeom prst="rect"/></p:spPr>
</p:pic>{}"#, label("01 Inherited gradient + transparent PNG"))).into_bytes(),
            "ppt/slides/slide2.xml" => *bytes = slide(
                r#"<p:bg><p:bgPr><a:solidFill><a:srgbClr val="111111"/></a:solidFill></p:bgPr></p:bg>"#,
                &format!("{}{}", cover(), label("02 Solid fill after gradient")),
            ).into_bytes(),
            "ppt/slides/_rels/slide1.xml.rels" => *bytes = relationships(&[
                ("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                ("rImage", "image", "../media/transparent-title.png"),
            ]).into_bytes(),
            "ppt/slides/_rels/slide2.xml.rels" => *bytes = relationships(&[
                ("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"),
            ]).into_bytes(),
            "ppt/slideLayouts/slideLayout1.xml" => *bytes = super::pptx_theme::layout()
                .replace(r#"<p:cSld name="Blank">"#, &format!(r#"<p:cSld name="Inherited gradient">{GRADIENT}"#)).into_bytes(),
            _ => (),
        }
    }
    let rotated = background_shape(3, [30, 30, 60, 100], false);
    let transformed = background_shape(5, [170, 40, 80, 90], true);
    let group = format!(
        r#"<p:grpSp><p:nvGrpSpPr><p:cNvPr id="4" name="Translated group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="254000" y="127000"/><a:ext cx="4064000" cy="2286000"/><a:chOff x="0" y="0"/><a:chExt cx="4064000" cy="2286000"/></a:xfrm></p:grpSpPr>
{transformed}</p:grpSp>"#
    );
    parts.push(xml(
        "ppt/slides/slide3.xml",
        &slide(
            "",
            &format!(
                "{}{rotated}{group}{}",
                cover(),
                label("03 Rotated and flipped background fills")
            ),
        ),
    ));
    parts.push(xml(
        "ppt/slides/_rels/slide3.xml.rels",
        &relationships(&[("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml")]),
    ));
    parts.push((
        "ppt/media/transparent-title.png",
        TRANSPARENT_TITLE.to_vec(),
    ));
    parts
}

fn slide(background: &str, shapes: &str) -> String {
    format!(
        r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld>{background}<p:spTree>{GROUP}{shapes}</p:spTree></p:cSld></p:sld>"#
    )
}

const fn cover() -> &'static str {
    r#"<p:sp><p:nvSpPr><p:cNvPr id="7" name="Solid cover"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>
<a:xfrm><a:off x="0" y="0"/><a:ext cx="4064000" cy="2286000"/></a:xfrm><a:prstGeom prst="rect"/>
<a:solidFill><a:srgbClr val="2A9D8F"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"#
}

fn background_shape(id: u32, rect: [u32; 4], flipped: bool) -> String {
    let [x, y, width, height] = rect.map(|value| value * 12700);
    let flip = if flipped { "1" } else { "0" };
    format!(
        r#"<p:sp useBgFill="1"><p:nvSpPr><p:cNvPr id="{id}" name="Slide background fill"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm rot="5400000" flipH="{flip}"><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm>
<a:prstGeom prst="rect"/><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"#
    )
}

fn label(text: &str) -> String {
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="6" name="Sample label"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="762000" y="1905000"/><a:ext cx="3175000" cy="254000"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr wrap="none" lIns="0" rIns="0" tIns="0" bIns="0"/><a:p><a:r><a:rPr sz="1100">
<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp>"#
    )
}
