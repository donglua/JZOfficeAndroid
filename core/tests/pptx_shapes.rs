pub mod support;

use jz_office_core::{
    model::{Document, ElementType},
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
