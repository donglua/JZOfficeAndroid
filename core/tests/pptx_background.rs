pub mod support;

use jz_office_core::{model::ElementType, parse_reader};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, replace, TestResult},
    pptx,
};

const GRADIENT: &str = r#"<p:bg><p:bgPr><a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FF6A28"/></a:gs><a:gs pos="100000"><a:srgbClr val="F43F12"/></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill></p:bgPr></p:bg>"#;
const TEXT: &str = r#"<p:sp><p:spPr><a:xfrm><a:off x="1270000" y="1270000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:t>Foreground</a:t></a:r></a:p></p:txBody></p:sp>"#;

fn part(root: &str, background: &str, content: &str) -> String {
    format!(
        r#"<p:{root} xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld>{background}<p:spTree>{content}</p:spTree></p:cSld></p:{root}>"#
    )
}

#[test]
fn inherited_linear_background_precedes_layout_foreground() -> TestResult {
    let mut parts = pptx::parts();
    replace(&mut parts, "ppt/slides/slide2.xml", &part("sld", "", ""));
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &part("sldLayout", GRADIENT, TEXT),
    );
    let doc = parse_reader(Cursor::new(archive(&parts)?))?;
    let page = &doc.pages[0];
    let background = page.elements.first().ok_or("background missing")?;
    assert!(matches!(background.kind, ElementType::RECT));
    checks::bounds(background, [0.0, 0.0, 720.0, 405.0]);
    let gradient = background
        .fill_gradient
        .as_ref()
        .ok_or("gradient missing")?;
    assert_eq!(gradient.colors, vec![0xffff6a28, 0xfff43f12]);
    assert_eq!(gradient.positions, vec![0.0, 1.0]);
    assert_eq!(gradient.angle, 90.0);
    assert!(!gradient.scaled);
    assert_eq!(checks::text(&page.elements[1].paragraphs), "Foreground");
    assert!(!doc.warnings.iter().any(|w| w.contains("gradient, pattern")));
    Ok(())
}

#[test]
fn nearest_background_wins_over_inherited_gradient() -> TestResult {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &part("sldLayout", GRADIENT, ""),
    );
    let doc = parse_reader(Cursor::new(archive(&parts)?))?;
    assert_eq!(doc.pages[0].background, 0xfff4f7fa);
    assert!(doc.pages[0]
        .elements
        .iter()
        .all(|e| e.fill_gradient.is_none()));
    Ok(())
}

#[test]
fn master_gradient_is_inherited_only_when_slide_and_layout_have_no_background() -> TestResult {
    let mut parts = pptx::parts();
    replace(&mut parts, "ppt/slides/slide2.xml", &part("sld", "", TEXT));
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &part("sldLayout", "", ""),
    );
    replace(
        &mut parts,
        "ppt/slideMasters/slideMaster1.xml",
        &part("sldMaster", GRADIENT, ""),
    );
    let doc = parse_reader(Cursor::new(archive(&parts)?))?;
    assert!(doc.pages[0].elements[0].fill_gradient.is_some());
    assert_eq!(
        checks::text(&doc.pages[0].elements[1].paragraphs),
        "Foreground"
    );
    Ok(())
}

#[test]
fn direct_gradient_background_remains_when_master_shapes_are_hidden() -> TestResult {
    let mut parts = pptx::parts();
    let slide = part("sld", GRADIENT, TEXT).replace("<p:sld ", "<p:sld showMasterSp=\"0\" ");
    replace(&mut parts, "ppt/slides/slide2.xml", &slide);
    let doc = parse_reader(Cursor::new(archive(&parts)?))?;
    assert!(doc.pages[0].elements[0].fill_gradient.is_some());
    assert_eq!(
        checks::text(&doc.pages[0].elements[1].paragraphs),
        "Foreground"
    );
    Ok(())
}

fn background_shape(background: &str, flag: &str) -> TestResult<serde_json::Value> {
    let mut parts = pptx::parts();
    let shape = format!(
        r#"<p:sp useBgFill="{flag}"><p:spPr><a:xfrm><a:off x="1270000" y="2540000"/><a:ext cx="5080000" cy="1270000"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr></p:sp>"#
    );
    replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &part("sld", "", &shape),
    );
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &part("sldLayout", background, ""),
    );
    let document = parse_reader(Cursor::new(archive(&parts)?))?;
    Ok(serde_json::to_value(&document.pages[0])?)
}

#[test]
fn use_background_fill_keeps_inherited_gradient_in_slide_coordinates() -> TestResult {
    let page = background_shape(GRADIENT, "1")?;
    let elements = page["elements"].as_array().ok_or("missing elements")?;
    let shape = elements.last().ok_or("missing shape")?;
    assert_eq!(
        shape["fillGradient"]["colors"],
        page["elements"][0]["fillGradient"]["colors"]
    );
    assert_eq!(
        shape["fillGradient"]["points"],
        page["elements"][0]["fillGradient"]["points"]
    );
    assert_eq!(
        shape["fillGradient"]["transform"],
        serde_json::json!([1.0, 0.0, 0.0, 1.0, -100.0, -200.0])
    );
    assert!(page["elements"][0]["fillGradient"]["transform"].is_null());
    assert_eq!(shape["x"], 100.0);
    assert_eq!(shape["y"], 200.0);
    Ok(())
}

#[test]
fn background_fill_overrides_local_fill_only_when_enabled() -> TestResult {
    let background =
        r#"<p:bg><p:bgPr><a:solidFill><a:srgbClr val="123456"/></a:solidFill></p:bgPr></p:bg>"#;
    for (flag, expected) in [
        ("1", 0xff123456u32),
        ("true", 0xff123456),
        ("0", 0xffff0000),
    ] {
        let page = background_shape(background, flag)?;
        assert_eq!(page["elements"][0]["fill"], expected);
        assert!(page["elements"][0]["fillGradient"].is_null());
    }
    Ok(())
}

#[test]
fn explicit_shape_background_fill_flag_overrides_its_placeholder() -> TestResult {
    let mut parts = pptx::parts();
    let placeholder = |flag: &str| {
        format!(
            r#"<p:sp {flag}><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="1270000"/></a:xfrm><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr></p:sp>"#
        )
    };
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &part("sldLayout", GRADIENT, &placeholder(r#"useBgFill="1""#)),
    );
    for (flag, expected) in [("", true), (r#"useBgFill="0""#, false)] {
        replace(
            &mut parts,
            "ppt/slides/slide2.xml",
            &part("sld", "", &placeholder(flag)),
        );
        let doc = parse_reader(Cursor::new(archive(&parts)?))?;
        let shape = doc.pages[0].elements.last().ok_or("missing shape")?;
        assert_eq!(
            shape
                .fill_gradient
                .as_ref()
                .is_some_and(|fill| fill.in_slide_space),
            expected
        );
        if !expected {
            assert_eq!(shape.fill, 0xffff0000);
        }
    }
    Ok(())
}
