use super::{
    package::{relationships, Parts},
    pptx,
    pptx_shapes::GROUP,
};

pub fn parts() -> Parts {
    let mut parts = pptx::parts();
    parts.retain(|(path, _)| *path != "ppt/media/pixel.png");
    let layout = relationships(&[("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml")]);
    for (path, bytes) in &mut parts {
        match *path {
            "ppt/presentation.xml" => {
                *bytes = pptx::PRESENTATION
                    .replace("9144000", "12192000")
                    .replace("5143500", "6858000")
                    .into_bytes();
            }
            "ppt/slides/slide2.xml" => *bytes = numbering().into_bytes(),
            "ppt/slides/slide1.xml" => *bytes = presets().into_bytes(),
            "ppt/slides/_rels/slide1.xml.rels" | "ppt/slides/_rels/slide2.xml.rels" => {
                *bytes = layout.as_bytes().to_vec();
            }
            _ => (),
        }
    }
    parts
}

fn numbering() -> String {
    let mut shapes = heading(
        "自动编号 · 16 种常见格式",
        "每组从第 4 项开始，下一项应自动递增为 5。核对数字、字母、罗马数字及括号。",
        "102238",
    );
    for ((scheme, title), index) in [
        ("arabicPeriod", "数字 · 句点"),
        ("arabicParenR", "数字 · 右括号"),
        ("arabicParenBoth", "数字 · 双括号"),
        ("arabicPlain", "数字 · 无标点"),
        ("alphaLcPeriod", "小写字母 · 句点"),
        ("alphaLcParenR", "小写字母 · 右括号"),
        ("alphaLcParenBoth", "小写字母 · 双括号"),
        ("alphaUcPeriod", "大写字母 · 句点"),
        ("alphaUcParenR", "大写字母 · 右括号"),
        ("alphaUcParenBoth", "大写字母 · 双括号"),
        ("romanLcPeriod", "小写罗马 · 句点"),
        ("romanLcParenR", "小写罗马 · 右括号"),
        ("romanLcParenBoth", "小写罗马 · 双括号"),
        ("romanUcPeriod", "大写罗马 · 句点"),
        ("romanUcParenR", "大写罗马 · 右括号"),
        ("romanUcParenBoth", "大写罗马 · 双括号"),
    ]
    .into_iter()
    .zip(0_u32..)
    {
        let x = 40 + index % 4 * 224;
        let y = 126 + index / 4 * 90;
        let id = 10 + index * 2;
        shapes.push_str(&label(id, [x, y, 208, 24], title, 1600, "24745C"));
        let mut paragraphs = String::new();
        for (text, start) in [("步骤一", " startAt=\"4\""), ("步骤二", "")] {
            paragraphs.push_str(&format!(
                r#"<a:p><a:pPr><a:lnSpc><a:spcPts val="2300"/></a:lnSpc><a:buAutoNum type="{scheme}"{start}/></a:pPr><a:r><a:rPr sz="1800"><a:solidFill><a:srgbClr val="102238"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r></a:p>"#
            ));
        }
        shapes.push_str(&text_shape(id + 1, [x, y + 26, 208, 54], &paragraphs));
    }
    shapes.push_str(&label(
        4,
        [40, 502, 880, 24],
        "预期：编号依次为 4/5、d/e、D/E、iv/v 或 IV/V，标点保持各组格式。",
        1600,
        "102238",
    ));
    slide(&shapes, "F4F7FA")
}

fn presets() -> String {
    let mut shapes = heading(
        "预设图形 · 三角形与菱形",
        "上排为默认轮廓，下排为顶点调整、水平翻转和旋转。填色与描边应保持完整。",
        "DCE7F3",
    );
    for ((title, preset, adjustment, attributes, color, expected), index) in [
        ("三角形", "triangle", "", "", "45CFC0", "顶点位于上边中点"),
        (
            "直角三角形",
            "rtTriangle",
            "",
            "",
            "E9C46A",
            "直角位于左下方",
        ),
        ("菱形", "diamond", "", "", "8CB9ED", "四个顶点位于边中点"),
        (
            "三角形 · 顶点调整",
            "triangle",
            r#"<a:gd name="adj" fmla="val 25000"/>"#,
            "",
            "45CFC0",
            "顶点位于宽度的 25% 处",
        ),
        (
            "直角三角形 · 水平翻转",
            "rtTriangle",
            "",
            r#" flipH="1""#,
            "E9C46A",
            "直角移至右下方",
        ),
        (
            "直角三角形 · 旋转 90°",
            "rtTriangle",
            "",
            r#" rot="5400000""#,
            "8CB9ED",
            "顺时针旋转，直角移至左上方",
        ),
    ]
    .into_iter()
    .zip(0_u32..)
    {
        let x = 40 + index % 3 * 298;
        let y = if index < 3 { 126 } else { 312 };
        let id = 10 + index * 3;
        shapes.push_str(&label(id, [x, y, 284, 26], title, 1800, "DCE7F3"));
        let [left, top, width, height] = [x + 72, y + 42, 128, 100].map(|value| value * 12700);
        shapes.push_str(&format!(
            r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="{title}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>
<a:xfrm{attributes}><a:off x="{left}" y="{top}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm>
<a:prstGeom prst="{preset}"><a:avLst>{adjustment}</a:avLst></a:prstGeom><a:solidFill><a:srgbClr val="{color}"/></a:solidFill>
<a:ln w="25400"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></p:spPr></p:sp>"#,
            id + 1
        ));
        let note_y = if index < 3 { 276 } else { 478 };
        shapes.push_str(&label(
            id + 2,
            [x, note_y, 284, 24],
            expected,
            1600,
            "DCE7F3",
        ));
    }
    slide(&shapes, "102238")
}

fn heading(title: &str, detail: &str, color: &str) -> String {
    format!(
        "{}{}",
        label(2, [40, 28, 880, 48], title, 3200, color),
        label(3, [40, 86, 880, 26], detail, 1700, color)
    )
}

fn label(id: u32, rect: [u32; 4], text: &str, size: u32, color: &str) -> String {
    text_shape(
        id,
        rect,
        &format!(
            r#"<a:p><a:r><a:rPr sz="{size}"><a:solidFill><a:srgbClr val="{color}"/></a:solidFill></a:rPr><a:t>{text}</a:t></a:r></a:p>"#
        ),
    )
}

fn text_shape(id: u32, rect: [u32; 4], paragraphs: &str) -> String {
    let [x, y, width, height] = rect.map(|value| value * 12700);
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Compatibility explanation"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr wrap="none" lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/>{paragraphs}</p:txBody></p:sp>"#
    )
}

fn slide(shapes: &str, background: &str) -> String {
    format!(
        r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="{background}"/></a:solidFill></p:bgPr></p:bg><p:spTree>{GROUP}{shapes}</p:spTree></p:cSld></p:sld>"#
    )
}
