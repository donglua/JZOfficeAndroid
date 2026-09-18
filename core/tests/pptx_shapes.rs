pub mod support;

use jz_office_core::{
    model::{Document, ElementType, PathCommand},
    parse_reader,
};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, replace, TestResult},
    pptx,
};

fn with_layout_panel(preset: &str, adjustments: &str) -> TestResult<Document> {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &format!(
            r#"
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank">
<p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="10" name="White panel"/>
<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>
<a:xfrm><a:off x="0" y="685800"/><a:ext cx="9144000" cy="4394200"/></a:xfrm>
<a:prstGeom prst="{preset}"><a:avLst>{adjustments}</a:avLst></a:prstGeom>
<a:solidFill><a:schemeClr val="bg1"/></a:solidFill><a:ln><a:noFill/></a:ln>
</p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>"#
        ),
    );
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

#[test]
fn zero_radius_layout_panel_matches_rectangle_before_slide_content() -> TestResult {
    let plain = with_layout_panel("rect", "")?;
    let square = with_layout_panel("roundRect", r#"<a:gd name="adj" fmla="val 0"/>"#)?;

    assert_eq!(
        serde_json::to_value(&square.pages)?,
        serde_json::to_value(&plain.pages)?
    );
    for page in &square.pages {
        let panel = &page.elements[0];
        assert!(matches!(panel.kind, ElementType::RECT));
        assert_eq!(panel.fill, 0xffffffff);
        assert_eq!(panel.stroke, 0);
        checks::bounds(panel, [0.0, 54.0, 720.0, 346.0]);
        assert!(matches!(page.elements[1].kind, ElementType::TEXT));
    }
    assert!(!square
        .warnings
        .iter()
        .any(|warning| warning.contains("complex geometry")));
    Ok(())
}

#[test]
fn default_nonzero_and_unresolved_rounding_are_not_treated_as_square() -> TestResult {
    for adjustments in [
        "",
        r#"<a:gd name="adj" fmla="val 16667"/>"#,
        r#"<a:gd name="adj" fmla="val invalid"/>"#,
        r#"<a:gd name="adj" fmla="val 0 extra"/>"#,
        r#"<a:gd name="other" fmla="val 0"/>"#,
    ] {
        let doc = with_layout_panel("roundRect", adjustments)?;
        for page in &doc.pages {
            assert!(
                matches!(page.elements[0].kind, ElementType::TEXT),
                "{adjustments}"
            );
        }
        assert!(doc
            .warnings
            .iter()
            .any(|warning| warning.contains("complex geometry")));
    }
    let custom = with_layout_panel("star5", r#"<a:gd name="adj" fmla="val 0"/>"#)?;
    assert!(matches!(
        custom.pages[0].elements[0].kind,
        ElementType::TEXT
    ));
    Ok(())
}

#[test]
fn custom_geometry_paths_are_preserved_as_local_commands() -> TestResult {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        r#"
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank">
<p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="10" name="Custom panel"/>
<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>
<a:xfrm><a:off x="0" y="685800"/><a:ext cx="9144000" cy="4394200"/></a:xfrm>
<a:custGeom><a:avLst/><a:gdLst/><a:pathLst><a:path w="100" h="50">
<a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="0"/></a:lnTo>
<a:cubicBezTo><a:pt x="100" y="20"/><a:pt x="80" y="50"/><a:pt x="0" y="50"/></a:cubicBezTo><a:close/>
</a:path></a:pathLst></a:custGeom><a:solidFill><a:srgbClr val="112233"/></a:solidFill>
<a:ln w="12700"><a:solidFill><a:srgbClr val="445566"/></a:solidFill></a:ln>
</p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>"#,
    );
    let document = parse_reader(Cursor::new(archive(&parts)?))?;
    let element = &document.pages[0].elements[0];
    assert!(matches!(element.kind, ElementType::PATH));
    assert_eq!(element.paths.len(), 1);
    assert_eq!(element.paths[0].commands.len(), 4);
    assert!(matches!(element.paths[0].commands[0], PathCommand::Move(_)));
    assert!(matches!(
        element.paths[0].commands[2],
        PathCommand::Cubic(_)
    ));
    assert_eq!(element.fill, 0xff112233);
    assert_eq!(element.stroke, 0xff445566);
    Ok(())
}
