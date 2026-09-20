use super::{
    package::{relationships, xml, Parts},
    pptx,
    pptx_shapes::GROUP,
};

const SLIDES: [(&str, &str, &str); 9] = [
    (
        "ppt/slides/slide1.xml",
        "ppt/slides/_rels/slide1.xml.rels",
        "slides/slide1.xml",
    ),
    (
        "ppt/slides/slide2.xml",
        "ppt/slides/_rels/slide2.xml.rels",
        "slides/slide2.xml",
    ),
    (
        "ppt/slides/slide3.xml",
        "ppt/slides/_rels/slide3.xml.rels",
        "slides/slide3.xml",
    ),
    (
        "ppt/slides/slide4.xml",
        "ppt/slides/_rels/slide4.xml.rels",
        "slides/slide4.xml",
    ),
    (
        "ppt/slides/slide5.xml",
        "ppt/slides/_rels/slide5.xml.rels",
        "slides/slide5.xml",
    ),
    (
        "ppt/slides/slide6.xml",
        "ppt/slides/_rels/slide6.xml.rels",
        "slides/slide6.xml",
    ),
    (
        "ppt/slides/slide7.xml",
        "ppt/slides/_rels/slide7.xml.rels",
        "slides/slide7.xml",
    ),
    (
        "ppt/slides/slide8.xml",
        "ppt/slides/_rels/slide8.xml.rels",
        "slides/slide8.xml",
    ),
    (
        "ppt/slides/slide9.xml",
        "ppt/slides/_rels/slide9.xml.rels",
        "slides/slide9.xml",
    ),
];

pub fn parts() -> Parts {
    let mut parts = pptx::parts();
    parts.retain(|(path, _)| !path.starts_with("ppt/slides/") && !path.starts_with("ppt/media/"));
    let ids: Vec<_> = (1..=9).map(|number| format!("rSlide{number}")).collect();
    let slide_ids: String = ids
        .iter()
        .enumerate()
        .map(|(index, id)| format!(r#"<p:sldId id="{}" r:id="{id}"/>"#, 256 + index))
        .collect();
    let mut slide_rels: Vec<_> = SLIDES
        .iter()
        .zip(&ids)
        .map(|((_, _, target), id)| (id.as_str(), "slide", *target))
        .collect();
    slide_rels.push(("rMaster", "slideMaster", "slideMasters/slideMaster1.xml"));
    for (path, bytes) in &mut parts {
        match *path {
            "ppt/presentation.xml" => {
                *bytes = pptx::PRESENTATION
                    .replace(
                        r#"<p:sldId id="257" r:id="rSecond"/><p:sldId id="256" r:id="rFirst"/>"#,
                        &slide_ids,
                    )
                    .replace("9144000", "7620000")
                    .replace("5143500", "3810000")
                    .into_bytes()
            }
            "ppt/_rels/presentation.xml.rels" => *bytes = relationships(&slide_rels).into_bytes(),
            "[Content_Types].xml" => {
                let extra: String = SLIDES.iter().skip(2).map(|(path, _, _)| format!(
                    r#"<Override PartName="/{path}" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"#
                )).collect();
                bytes.splice(
                    bytes.len() - "</Types>".len()..bytes.len() - "</Types>".len(),
                    extra.bytes(),
                );
            }
            _ => (),
        }
    }
    for (index, (path, rels, _)) in SLIDES.into_iter().enumerate() {
        let alignment = ["l", "ctr", "r"][index / 3];
        let (wrap, text, height, label) = match index % 3 {
            0 => ("none", "PART 0", 80, "No wrap: overflow stays on one line"),
            1 => (
                "none",
                "PART&#10;0",
                160,
                "Explicit newline: exactly two lines",
            ),
            _ => (
                "square",
                "PART 0",
                240,
                "Normal wrap: text uses the narrow box",
            ),
        };
        let title = if index % 3 == 0 {
            text_box(2, [200, 110, 370, 40], ("Section title", 2400))
        } else {
            String::new()
        };
        let caption = text_box(
            3,
            [20, 270, 560, 25],
            (
                &format!(
                    "{} / {} - {label}",
                    index + 1,
                    ["Left", "Center", "Right"][index / 3]
                ),
                1400,
            ),
        );
        let height = height * 12700;
        let slide = format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="000000"/></a:solidFill></p:bgPr></p:bg><p:spTree>{GROUP}
<p:sp><p:nvSpPr><p:cNvPr id="4" name="Wrapping comparison"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="2540000" y="127000"/><a:ext cx="1905000" cy="{height}"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr wrap="{wrap}" lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/>
<a:p><a:pPr algn="{alignment}"><a:spcAft><a:spcPts val="0"/></a:spcAft></a:pPr>
<a:r><a:rPr sz="6000"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r>
<a:r><a:rPr sz="6000"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:rPr><a:t>5</a:t></a:r>
</a:p></p:txBody></p:sp>{title}{caption}</p:spTree></p:cSld></p:sld>"#
        );
        parts.push(xml(path, &slide));
        parts.push(xml(
            rels,
            &relationships(&[("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml")]),
        ));
    }
    parts
}

fn text_box(id: u32, rect: [u32; 4], text: (&str, u32)) -> String {
    let (value, size) = text;
    let [x, y, width, height] = rect.map(|value| value * 12700);
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Sample label"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr wrap="none" lIns="0" rIns="0" tIns="0" bIns="0"/><a:p><a:r><a:rPr sz="{size}">
<a:solidFill><a:srgbClr val="CCCCCC"/></a:solidFill></a:rPr><a:t>{value}</a:t></a:r></a:p></p:txBody></p:sp>"#
    )
}
