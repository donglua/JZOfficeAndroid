pub mod support;

use jz_office_core::{model::ElementType, parse_reader};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, replace, TestResult},
    pptx,
};

fn document(content: &str) -> TestResult<jz_office_core::model::Document> {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:cSld><p:spTree>{content}</p:spTree></p:cSld></p:sld>"#
        ),
    );
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

#[test]
fn group_coordinate_units_do_not_scale_strokes_fonts_or_insets() -> TestResult {
    let doc = document(&support::pptx_compat::coordinate_group())?;
    let elements = &doc.pages[0].elements;
    let text = checks::element(elements, ElementType::TEXT)?;
    assert_eq!(checks::text(&text.paragraphs), "Coordinate units");
    checks::near(text.width, 485.6);
    checks::near(text.height, 32.8);
    checks::near(text.paragraphs[0].runs[0].size, 24.0);
    let rect = checks::element(elements, ElementType::RECT)?;
    checks::near(rect.width, 80.0);
    checks::near(rect.height, 25.0);
    checks::near(rect.stroke_width, 2.0);
    let [a, b, c, d, x, y] = rect.transform;
    checks::near(a.hypot(b), 1.0);
    checks::near(c.hypot(d), 1.0);
    checks::near(x, 600.0);
    checks::near(y, 365.0);
    Ok(())
}

fn group(content: &str, attrs: &str) -> String {
    format!(
        r#"<p:grpSp><p:nvGrpSpPr><p:cNvPr id="9" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm {attrs}><a:off x="1270000" y="635000"/><a:ext cx="2540000" cy="1270000"/><a:chOff x="127000" y="254000"/><a:chExt cx="1270000" cy="1270000"/></a:xfrm></p:grpSpPr>{content}</p:grpSp>"#
    )
}

fn shape() -> &'static str {
    r#"<p:sp><p:spPr><a:xfrm><a:off x="254000" y="381000"/><a:ext cx="254000" cy="127000"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="22AA66"/></a:solidFill></p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:t>Grouped</a:t></a:r></a:p></p:txBody></p:sp>"#
}

#[test]
fn grouped_shapes_retain_drawing_order_and_text() -> TestResult {
    let doc = document(&group(shape(), ""))?;
    let elements = &doc.pages[0].elements;
    assert_eq!(elements.len(), 2);
    assert!(matches!(elements[0].kind, ElementType::RECT));
    assert_eq!(checks::text(&elements[1].paragraphs), "Grouped");
    let json = serde_json::to_value(&elements[0])?;
    assert_eq!(
        json["transform"],
        serde_json::json!([1.0, 0.0, 0.0, 1.0, 120.0, 60.0])
    );
    checks::bounds(&elements[0], [0.0, 0.0, 40.0, 10.0]);
    Ok(())
}

#[test]
fn nested_group_transforms_compose_in_parent_order() -> TestResult {
    let doc = document(&group(&group(shape(), ""), ""))?;
    let element = checks::element(&doc.pages[0].elements, ElementType::RECT)?;
    let json = serde_json::to_value(element)?;
    assert_eq!(
        json["transform"],
        serde_json::json!([1.0, 0.0, 0.0, 1.0, 320.0, 90.0])
    );
    checks::bounds(element, [0.0, 0.0, 80.0, 10.0]);
    Ok(())
}

#[test]
fn group_rotation_uses_group_center() -> TestResult {
    let doc = document(&group(shape(), r#"rot="5400000""#))?;
    let element = checks::element(&doc.pages[0].elements, ElementType::RECT)?;
    let json = serde_json::to_value(element)?;
    let transform = json["transform"].as_array().ok_or("missing transform")?;
    for (actual, expected) in transform.iter().zip([0.0, 1.0, -1.0, 0.0, 240.0, 20.0]) {
        assert!((actual.as_f64().ok_or("bad coordinate")? - expected).abs() < 0.001);
    }
    checks::bounds(element, [0.0, 0.0, 40.0, 10.0]);
    Ok(())
}

#[test]
fn hidden_or_invalid_groups_do_not_leak_their_children() -> TestResult {
    for xml in [
        group(shape(), "").replace("name=\"Group\"", "name=\"Group\" hidden=\"1\""),
        group(shape(), "").replace("chExt cx=\"1270000\"", "chExt cx=\"0\""),
        group(shape(), "").replace("chExt cx=\"1270000\"", "chExt cx=\"1\""),
    ] {
        let doc = document(&xml)?;
        assert!(doc.pages[0].elements.is_empty());
    }
    Ok(())
}

#[test]
fn picture_flips_are_composed_into_the_drawing_transform() -> TestResult {
    let doc = document(
        r#"<p:pic><p:blipFill><a:blip r:embed="rImage"/></p:blipFill><p:spPr><a:xfrm flipH="1" flipV="true"><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm></p:spPr></p:pic>"#,
    )?;
    let image = checks::element(&doc.pages[0].elements, ElementType::IMAGE)?;
    let json = serde_json::to_value(image)?;
    assert_eq!(
        json["transform"],
        serde_json::json!([-1.0, 0.0, 0.0, -1.0, 100.0, 50.0])
    );
    Ok(())
}

#[test]
fn groups_count_toward_the_existing_object_budget() -> TestResult {
    let empty = group("", "");
    let error = match document(&empty.repeat(5001)) {
        Ok(_) => return Err("group budget must apply even without leaves".into()),
        Err(error) => error,
    };
    assert!(error.to_string().contains("5000"));
    Ok(())
}

#[test]
fn children_exceeding_transformed_coordinate_limits_are_omitted() -> TestResult {
    let child = shape().replace("off x=\"254000\"", "off x=\"1269000000\"");
    let doc = document(&group(&child, ""))?;
    assert!(doc.pages[0].elements.is_empty());
    assert!(doc
        .warnings
        .iter()
        .any(|w| w.contains("transformed bounds")));
    Ok(())
}

#[test]
fn nonuniform_parent_scale_precedes_distinct_rotated_child_transform() -> TestResult {
    let nested = group(shape(), r#"rot="2700000""#)
        .replace(
            "off x=\"1270000\" y=\"635000\"",
            "off x=\"254000\" y=\"381000\"",
        )
        .replace(
            "ext cx=\"2540000\" cy=\"1270000\"",
            "ext cx=\"1270000\" cy=\"1270000\"",
        );
    let doc = document(&group(&nested, ""))?;
    let e = checks::element(&doc.pages[0].elements, ElementType::RECT)?;
    let [a, b, c, d, tx, ty] = e.transform;
    let s = 0.5_f32.sqrt();
    for (u, v) in [(0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 1.0)] {
        let (x, y) = (20.0 + 20.0 * u, 30.0 + 10.0 * v);
        checks::near(
            a * u * e.width + c * v * e.height + tx,
            220.0 + 2.0 * s * (x - y + 10.0),
        );
        checks::near(
            b * u * e.width + d * v * e.height + ty,
            110.0 + s * (x + y - 130.0),
        );
    }
    Ok(())
}

#[test]
fn leaf_rotation_survives_group_coordinate_normalization() -> TestResult {
    let leaf = shape().replace("<a:xfrm>", "<a:xfrm rot=\"5400000\" flipH=\"1\">");
    let doc = document(&group(&leaf, ""))?;
    let rect = checks::element(&doc.pages[0].elements, ElementType::RECT)?;
    checks::bounds(rect, [0.0, 0.0, 20.0, 20.0]);
    checks::near(rect.rotation, 0.0);
    assert!(rect.flip_h);
    for (actual, expected) in rect
        .transform
        .iter()
        .zip([0.0, -1.0, -1.0, 0.0, 150.0, 75.0])
    {
        checks::near(*actual, expected);
    }
    Ok(())
}

#[test]
fn hidden_nested_group_preserves_visible_siblings_and_layer_order() -> TestResult {
    let first = shape().replace("22AA66", "FF0000");
    let last = shape().replace("22AA66", "0000FF");
    let hidden = group(shape(), "").replace("name=\"Group\"", "name=\"Group\" hidden=\"true\"");
    let doc = document(&group(&format!("{first}{hidden}{last}"), ""))?;
    let colors: Vec<_> = doc.pages[0]
        .elements
        .iter()
        .filter(|e| matches!(e.kind, ElementType::RECT))
        .map(|e| e.fill)
        .collect();
    assert_eq!(colors, [0xffff0000, 0xff0000ff]);
    Ok(())
}

#[test]
fn missing_group_coordinates_are_omitted_with_warning() -> TestResult {
    let malformed = group(shape(), "").replace("<a:chOff x=\"127000\" y=\"254000\"/>", "");
    let doc = document(&malformed)?;
    assert!(doc.pages[0].elements.is_empty());
    assert!(doc
        .warnings
        .iter()
        .any(|w| w.contains("groups with invalid")));
    Ok(())
}

#[test]
fn unsupported_group_flip_warns_and_preserves_unflipped_contents() -> TestResult {
    let doc = document(&group(shape(), r#"flipH="1""#))?;
    assert_eq!(doc.pages[0].elements.len(), 2);
    assert!(doc.warnings.iter().any(|w| w.contains("group flips")));
    Ok(())
}
