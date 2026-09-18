use super::{package::Parts, pptx};

pub fn parts() -> Parts {
    let numbers: String = (1..=5)
        .map(|number| {
            text_box(
                number,
                [1_143_000 + (number - 1) * 2_159_000, 1_905_000, 782_955, 1_088_390],
                &format!(r#"<a:p><a:pPr algn="ctr"><a:lnSpc><a:spcPct val="180000"/></a:lnSpc></a:pPr>
<a:r><a:rPr sz="3600"><a:latin typeface="Impact"/><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:rPr>
<a:t>0 {number}</a:t></a:r></a:p>"#),
            )
        })
        .collect();
    let heading = text_box(
        1,
        [1_104_265, 2_071_370, 3_199_130, 1_014_730],
        r#"<a:p><a:pPr algn="ctr"/><a:r><a:rPr sz="6000" b="1">
<a:latin typeface="Source Han Sans CN Light"/><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>
</a:rPr><a:t>PART 01</a:t></a:r></a:p>"#,
    );
    let title = text_box(
        2,
        [1_104_265, 2_335_530, 9_839_325, 1_918_970],
        r#"<a:p><a:pPr algn="l"><a:lnSpc><a:spcPct val="180000"/></a:lnSpc></a:pPr>
<a:r><a:rPr sz="6600"><a:latin typeface="Source Han Sans CN Regular"/>
<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:rPr><a:t>章节标题，完整显示</a:t></a:r></a:p>"#,
    );
    let mut parts = pptx::parts();
    parts.retain(|(path, _)| *path != "ppt/media/pixel.png");
    for (path, bytes) in &mut parts {
        match *path {
            "ppt/presentation.xml" => {
                *bytes = pptx::PRESENTATION
                    .replace("9144000", "12192000")
                    .replace("5143500", "6858000")
                    .into_bytes();
            }
            "ppt/slides/slide2.xml" => *bytes = slide(&numbers).into_bytes(),
            "ppt/slides/slide1.xml" => *bytes = slide(&format!("{heading}{title}")).into_bytes(),
            "ppt/slides/_rels/slide2.xml.rels" => {
                *bytes = super::package::relationships(&[(
                    "rLayout",
                    "slideLayout",
                    "../slideLayouts/slideLayout1.xml",
                )])
                .into_bytes();
            }
            _ => (),
        }
    }
    parts
}

fn slide(shapes: &str) -> String {
    format!(
        r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="0B3479"/></a:solidFill></p:bgPr></p:bg>
<p:spTree>{shapes}</p:spTree></p:cSld></p:sld>"#
    )
}

fn text_box(id: u32, rect: [u32; 4], paragraphs: &str) -> String {
    let [x, y, width, height] = rect;
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Text {id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr/><a:lstStyle/>{paragraphs}</p:txBody></p:sp>"#
    )
}
