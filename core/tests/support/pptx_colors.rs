use super::{package::Parts, pptx, pptx_shapes::GROUP, pptx_theme};

pub const GOLD_GRADIENT: &str = r#"<a:gradFill><a:gsLst>
<a:gs pos="30000"><a:srgbClr val="FFFAEB"/></a:gs>
<a:gs pos="100000"><a:schemeClr val="accent4"><a:lumMod val="40000"/><a:lumOff val="60000"/></a:schemeClr></a:gs>
</a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>"#;

pub fn parts() -> Parts {
    let mut parts = pptx::parts();
    for (name, bytes) in &mut parts {
        match *name {
            "ppt/slides/slide2.xml" => *bytes = slide(GOLD_GRADIENT).into_bytes(),
            "ppt/slides/slide1.xml" => {
                *bytes =
                    slide(r#"<a:solidFill><a:srgbClr val="FFF3DD"/></a:solidFill>"#).into_bytes();
            }
            "ppt/theme/theme1.xml" => {
                *bytes = pptx_theme::theme()
                    .replace(
                        r#"<a:accent4><a:srgbClr val="457B9D"/></a:accent4>"#,
                        r#"<a:accent4><a:srgbClr val="FFBA55"/></a:accent4>"#,
                    )
                    .into_bytes();
            }
            _ => {}
        }
    }
    parts
}

pub fn showcase_parts() -> Parts {
    let mut parts = parts();
    let gold = r#"<a:solidFill><a:srgbClr val="FFC852"/></a:solidFill>"#;
    let white = r#"<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>"#;
    let gradient = r#"<a:gradFill><a:gsLst>
<a:gs pos="0"><a:srgbClr val="FFD54F"/></a:gs>
<a:gs pos="100000"><a:schemeClr val="accent4"/></a:gs>
</a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>"#;
    for (name, bytes) in &mut parts {
        let rows = match *name {
            "ppt/slides/slide2.xml" => [
                ("渐变输入 · 中点色预览", gradient),
                ("等效纯色 · #FFC852", gold),
                ("白色参照 · #FFFFFF", white),
            ],
            "ppt/slides/slide1.xml" => [
                ("亮金色 · #FFC852", gold),
                (
                    "浅金色 · #FFF3DD",
                    r#"<a:solidFill><a:srgbClr val="FFF3DD"/></a:solidFill>"#,
                ),
                ("白色参照 · #FFFFFF", white),
            ],
            _ => continue,
        };
        let mut content = String::new();
        for (row, (label, fill)) in (0_u32..).zip(rows) {
            for (id, y, size, bold, color, text) in [
                (
                    row * 2 + 2,
                    20 + row * 126,
                    2000,
                    0,
                    r#"<a:solidFill><a:srgbClr val="C3D1E0"/></a:solidFill>"#,
                    label,
                ),
                (
                    row * 2 + 3,
                    50 + row * 126,
                    5200,
                    1,
                    fill,
                    "文字填色 Aa 123",
                ),
            ] {
                let y = y * 12700;
                content.push_str(&format!(
                    r#"<p:sp><p:nvSpPr><p:cNvPr id="{id}" name="Text color comparison"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="508000" y="{y}"/><a:ext cx="8128000" cy="914400"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/><a:p>
<a:r><a:rPr sz="{size}" b="{bold}">{color}</a:rPr><a:t>{text}</a:t></a:r>
</a:p></p:txBody></p:sp>"#
                ));
            }
        }
        *bytes = format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="172B46"/></a:solidFill></p:bgPr></p:bg>
<p:spTree>{GROUP}{content}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"#
        )
        .into_bytes();
    }
    parts
}

fn slide(fill: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:bgPr></p:bg>
<p:spTree>{GROUP}<p:sp><p:nvSpPr><p:cNvPr id="2" name="Gold title"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="1270000" y="1270000"/><a:ext cx="6604000" cy="1778000"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>
<p:txBody><a:bodyPr lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/><a:p>
<a:r><a:rPr lang="en-US" sz="6400">{fill}</a:rPr><a:t>Gold title</a:t></a:r>
</a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"#
    )
}
