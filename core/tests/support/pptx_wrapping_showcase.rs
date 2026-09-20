use super::{
    package::{relationships, xml, Parts, TestResult},
    pptx,
    pptx_shapes::GROUP,
    pptx_wrapping,
};

pub fn parts() -> TestResult<Parts> {
    let examples = pptx_wrapping::parts();
    let slides = [
        comparison(0, &examples)?,
        comparison(1, &examples)?,
        comparison(2, &examples)?,
    ];
    let layout = relationships(&[("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml")]);
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
                .replace("9144000", "12192000").replace("5143500", "6858000").into_bytes(),
            "ppt/_rels/presentation.xml.rels" => *bytes = relationships(&[
                ("rFirst", "slide", "slides/slide1.xml"),
                ("rSecond", "slide", "slides/slide2.xml"),
                ("rThird", "slide", "slides/slide3.xml"),
                ("rMaster", "slideMaster", "slideMasters/slideMaster1.xml"),
            ]).into_bytes(),
            "ppt/slides/slide1.xml" => *bytes = slides[0].as_bytes().to_vec(),
            "ppt/slides/slide2.xml" => *bytes = slides[1].as_bytes().to_vec(),
            "ppt/slides/_rels/slide1.xml.rels" | "ppt/slides/_rels/slide2.xml.rels" => *bytes = layout.as_bytes().to_vec(),
            _ => (),
        }
    }
    parts.push(xml("ppt/slides/slide3.xml", &slides[2]));
    parts.push(xml("ppt/slides/_rels/slide3.xml.rels", &layout));
    Ok(parts)
}

fn comparison(alignment: usize, examples: &Parts) -> TestResult<String> {
    let (name, reference) = [
        ("左对齐", "文字以青色框的左边缘为起点"),
        ("居中对齐", "文字以青色框的中心为基准"),
        ("右对齐", "文字以青色框的右边缘为终点"),
    ][alignment];
    let mut shapes = label(2, [40, 30, 880, 46], (&format!("换行对照 · {name}"), 3200));
    shapes.push_str(&label(3, [40, 86, 880, 30], (reference, 1800)));
    for (column, (title, condition, expected, detail)) in [
        (
            "01 不自动换行",
            "wrap=none · 无换行符",
            "预期：文字保持一行",
            "超出青色框也不折行",
        ),
        (
            "02 显式换行",
            "wrap=none · 含换行符",
            "预期：在指定位置断行",
            "PART 与 05 分为两行",
        ),
        (
            "03 自动换行",
            "wrap=square · 无换行符",
            "预期：按青色框宽折行",
            "具体断点随系统字体变化",
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let path = format!("ppt/slides/slide{}.xml", alignment * 3 + column + 1);
        let (_, bytes) = examples
            .iter()
            .find(|(name, _)| *name == path)
            .ok_or("wrapping case missing")?;
        let source = std::str::from_utf8(bytes)?;
        let document = roxmltree::Document::parse(source)?;
        let body = document
            .descendants()
            .find(|node| node.has_tag_name("txBody"))
            .ok_or("wrapping text missing")?;
        let body = source[body.range()].replace(r#"sz="6000""#, r#"sz="4800""#);
        let column = u32::try_from(column)?;
        let id = 10 + column * 10;
        let x = 40 + column * 296;
        shapes.push_str(&label(id, [x, 150, 280, 30], (title, 2200)));
        shapes.push_str(&label(id + 1, [x, 190, 280, 24], (condition, 1600)));
        let frame = [x + 76, 230, 120, 144];
        let border = transform(frame);
        shapes.push_str(&format!(r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="Text box boundary"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr>{border}<a:prstGeom prst="rect"/><a:noFill/><a:ln w="12700"><a:solidFill><a:srgbClr val="45CFC0"/></a:solidFill></a:ln></p:spPr></p:sp>"#, id + 2));
        shapes.push_str(&text_shape(id + 3, frame, &body));
        shapes.push_str(&label(id + 4, [x, 398, 280, 26], (expected, 1800)));
        shapes.push_str(&label(id + 5, [x, 436, 280, 26], (detail, 1700)));
    }
    shapes.push_str(&label(
        4,
        [40, 498, 880, 26],
        (
            "青色框宽 120 pt，字号 48 pt。三栏文字相同，仅中间一栏增加显式换行符。",
            1700,
        ),
    ));
    Ok(format!(
        r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="102238"/></a:solidFill></p:bgPr></p:bg>
<p:spTree>{GROUP}{shapes}</p:spTree></p:cSld></p:sld>"#
    ))
}

fn label(id: u32, rect: [u32; 4], text: (&str, u32)) -> String {
    let (text, size) = text;
    text_shape(
        id,
        rect,
        &format!(
            r#"<p:txBody><a:bodyPr wrap="none" lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/>
<a:p><a:r><a:rPr sz="{size}"><a:solidFill><a:srgbClr val="DCE7F3"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r></a:p></p:txBody>"#
        ),
    )
}

fn text_shape(id: u32, rect: [u32; 4], body: &str) -> String {
    let transform = transform(rect);
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Comparison text"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr>{transform}<a:noFill/></p:spPr>{body}</p:sp>"#
    )
}

fn transform(rect: [u32; 4]) -> String {
    let [x, y, width, height] = rect.map(|value| value * 12700);
    format!(r#"<a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm>"#)
}
