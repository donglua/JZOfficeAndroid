pub mod support;

use jz_office_core::{model::Document, parse_reader};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, replace, TestResult},
    pptx,
};

const GOLD: &str = r#"<a:gradFill><a:gsLst>
<a:gs pos="30000"><a:srgbClr val="FFFAEB"/></a:gs>
<a:gs pos="100000"><a:schemeClr val="accent4"><a:lumMod val="40000"/><a:lumOff val="60000"/></a:schemeClr></a:gs>
</a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>"#;

fn document(body: &str) -> TestResult<Document> {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &format!(
            r#"<p:sld
xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:spTree><p:sp><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="6350000" cy="2540000"/></a:xfrm></p:spPr>
<p:txBody><a:bodyPr/>{body}</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"#
        ),
    );
    let theme = parts
        .iter()
        .find(|(name, _)| *name == "ppt/theme/theme1.xml")
        .ok_or("missing theme")?;
    let source = String::from_utf8(theme.1.clone())?.replace("457B9D", "FFBA55");
    replace(&mut parts, "ppt/theme/theme1.xml", &source);
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

fn color(fill: &str) -> TestResult<u32> {
    let doc = document(&format!(
        r#"<a:p><a:r><a:rPr>{fill}</a:rPr><a:t>Title</a:t></a:r></a:p>"#
    ))?;
    let text = checks::element(
        &doc.pages[0].elements,
        jz_office_core::model::ElementType::TEXT,
    )?;
    Ok(text.paragraphs[0].runs[0].color)
}

#[test]
fn gradient_title_uses_its_light_gold_midpoint_instead_of_default_black() -> TestResult {
    assert_eq!(color(GOLD)?, 0xfffff3dd);
    assert_eq!(
        color(r#"<a:solidFill><a:srgbClr val="FFF3DD"/></a:solidFill>"#)?,
        0xfffff3dd
    );
    Ok(())
}

#[test]
fn luminance_modulation_and_offset_preserve_hue_and_saturation() -> TestResult {
    for (source, transforms, expected) in [
        (
            "FFBA55",
            r#"<a:lumMod val="40000"/><a:lumOff val="60000"/>"#,
            0xffffe3bb,
        ),
        ("FF0000", r#"<a:lumMod val="50000"/>"#, 0xff800000),
        (
            "FF0000",
            r#"<a:lumMod val="50000"/><a:lumOff val="50000"/>"#,
            0xffff8080,
        ),
        (
            "808080",
            r#"<a:lumMod val="0"/><a:lumOff val="50000"/>"#,
            0xff808080,
        ),
        (
            "FF0000",
            r#"<a:lumMod val="0"/><a:lumOff val="50000"/>"#,
            0xffff0000,
        ),
        ("FF0000", r#"<a:lumOff val="-25000"/>"#, 0xff800000),
        ("FF0000", r#"<a:lumMod val="200000"/>"#, 0xffffffff),
    ] {
        assert_eq!(
            color(&format!(
                r#"<a:solidFill><a:srgbClr val="{source}">{transforms}</a:srgbClr></a:solidFill>"#
            ))?,
            expected
        );
    }
    Ok(())
}

#[test]
fn gradient_midpoint_honors_stop_positions_order_and_transparency() -> TestResult {
    for (stops, expected) in [
        (
            r#"<a:gs pos="100000"><a:srgbClr val="FFFFFF"/></a:gs><a:gs pos="0"><a:srgbClr val="000000"/></a:gs>"#,
            0xff808080,
        ),
        (
            r#"<a:gs pos="70000"><a:srgbClr val="123456"/></a:gs><a:gs pos="100000"><a:srgbClr val="FFFFFF"/></a:gs>"#,
            0xff123456,
        ),
        (
            r#"<a:gs pos="0"><a:srgbClr val="FF0000"><a:alpha val="0"/></a:srgbClr></a:gs><a:gs pos="100000"><a:srgbClr val="FFFFFF"/></a:gs>"#,
            0x80ffffff,
        ),
        (
            r#"<a:gs pos="50000"><a:srgbClr val="123456"/></a:gs><a:gs pos="invalid"><a:srgbClr val="000000"/></a:gs><a:gs pos="100001"><a:srgbClr val="000000"/></a:gs>"#,
            0xff123456,
        ),
    ] {
        assert_eq!(
            color(&format!(
                "<a:gradFill><a:gsLst>{stops}</a:gsLst></a:gradFill>"
            ))?,
            expected
        );
    }
    Ok(())
}

#[test]
fn luminance_respects_transform_order_and_alpha() -> TestResult {
    for (transforms, expected) in [
        (
            r#"<a:lumMod val="50000"/><a:tint val="50000"/>"#,
            0xffbf7f7f,
        ),
        (
            r#"<a:tint val="50000"/><a:lumMod val="50000"/>"#,
            0xffbf0000,
        ),
        (
            r#"<a:lumMod val="50000"/><a:alpha val="50000"/><a:lumOff val="50000"/>"#,
            0x80ff8080,
        ),
        (r#"<a:lumOff val="NaN"/>"#, 0xffff0000),
    ] {
        assert_eq!(
            color(&format!(
                r#"<a:solidFill><a:srgbClr val="FF0000">{transforms}</a:srgbClr></a:solidFill>"#
            ))?,
            expected
        );
    }
    Ok(())
}

#[test]
fn direct_solid_and_no_fill_override_inherited_gradient() -> TestResult {
    let doc = document(&format!(
        r#"<a:p><a:pPr><a:defRPr>{GOLD}</a:defRPr></a:pPr>
<a:r><a:t>Inherited</a:t></a:r>
<a:r><a:rPr><a:solidFill><a:srgbClr val="123456"/></a:solidFill></a:rPr><a:t>Solid</a:t></a:r>
<a:r><a:rPr><a:noFill/></a:rPr><a:t>Hidden</a:t></a:r></a:p>"#
    ))?;
    let text = checks::element(
        &doc.pages[0].elements,
        jz_office_core::model::ElementType::TEXT,
    )?;
    let colors: Vec<_> = text.paragraphs[0].runs.iter().map(|r| r.color).collect();
    assert_eq!(colors, [0xfffff3dd, 0xff123456, 0]);
    Ok(())
}
