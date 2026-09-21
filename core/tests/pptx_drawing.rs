pub mod support;

use jz_office_core::parse_reader;
use std::io::Cursor;
use support::package::{archive, replace, TestResult};

fn slide(content: &str) -> TestResult<serde_json::Value> {
    let mut parts = support::pptx::parts();
    replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:cSld>{content}</p:cSld></p:sld>"#
        ),
    );
    let document = parse_reader(Cursor::new(archive(&parts)?))?;
    Ok(serde_json::to_value(document)?)
}

fn near_array(actual: &serde_json::Value, expected: &[f64]) -> TestResult {
    let values = actual.as_array().ok_or("missing geometry array")?;
    assert_eq!(values.len(), expected.len());
    for (actual, expected) in values.iter().zip(expected) {
        let actual = actual.as_f64().ok_or("invalid geometry coordinate")?;
        assert!((actual - expected).abs() < 0.001, "{actual} != {expected}");
    }
    Ok(())
}

#[test]
fn shape_transform_includes_position_centered_rotation_and_flip() -> TestResult {
    let json = slide(
        r#"<p:spTree><p:sp><p:spPr><a:xfrm rot="5400000" flipH="1"><a:off x="127000" y="254000"/><a:ext cx="508000" cy="254000"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr></p:sp></p:spTree>"#,
    )?;
    let element = &json["pages"][0]["elements"][0];
    near_array(&element["transform"], &[0.0, -1.0, -1.0, 0.0, 40.0, 50.0])?;
    assert_eq!(json["schemaVersion"], 9);
    for field in ["rotation", "flipH", "flipV", "imageCrop"] {
        assert!(
            element.get(field).is_none(),
            "obsolete drawing field {field}"
        );
    }
    Ok(())
}

#[test]
fn rounded_transform_over_coordinate_limit_is_omitted_with_warning() -> TestResult {
    let json = slide(
        r#"<p:spTree><p:sp><p:spPr><a:xfrm rot="1800000" flipH="1"><a:off x="1269985024" y="0"/><a:ext cx="12700" cy="12700"/></a:xfrm><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr></p:sp></p:spTree>"#,
    )?;
    assert_eq!(json["pages"][0]["elements"], serde_json::json!([]));
    assert!(json["warnings"]
        .as_array()
        .ok_or("missing warnings")?
        .iter()
        .any(|warning| warning == "PPTX elements with excessive transformed bounds were omitted."));
    Ok(())
}

#[test]
fn gradient_endpoints_account_for_aspect_ratio_and_scaled_flag() -> TestResult {
    for (scaled, expected) in [
        ("1", [0.0, 0.0, 200.0, 100.0]),
        ("0", [25.0, -25.0, 175.0, 125.0]),
    ] {
        let json = slide(&format!(
            r#"<p:spTree><p:sp><p:spPr><a:xfrm><a:off x="127000" y="254000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm><a:prstGeom prst="rect"/>{}</p:spPr></p:sp></p:spTree>"#,
            gradient("2700000", scaled)
        ))?;
        let fill = &json["pages"][0]["elements"][0]["fillGradient"];
        near_array(&fill["points"], &expected)?;
        assert!(fill["transform"].is_null());
        for field in ["angle", "scaled", "inSlideSpace"] {
            assert!(fill.get(field).is_none());
        }
    }
    Ok(())
}

#[test]
fn cropped_image_has_local_destination_bounds() -> TestResult {
    let json = slide(
        r#"<p:spTree><p:pic><p:blipFill><a:blip r:embed="rImage"/><a:srcRect l="25000" t="25000"/></p:blipFill><p:spPr><a:xfrm flipV="1"><a:off x="127000" y="254000"/><a:ext cx="762000" cy="381000"/></a:xfrm></p:spPr></p:pic></p:spTree>"#,
    )?;
    let image = &json["pages"][0]["elements"][0];
    near_array(&image["imageBounds"], &[-20.0, -10.0, 60.0, 30.0])?;
    near_array(&image["transform"], &[1.0, 0.0, 0.0, -1.0, 10.0, 50.0])
}

#[test]
fn background_gradient_keeps_slide_coordinates_under_rotated_flipped_shape() -> TestResult {
    let json = slide(&format!(
        r#"<p:bg><p:bgPr>{}</p:bgPr></p:bg><p:spTree><p:sp useBgFill="1"><p:spPr><a:xfrm rot="5400000" flipH="1"><a:off x="127000" y="254000"/><a:ext cx="508000" cy="254000"/></a:xfrm><a:prstGeom prst="rect"/></p:spPr></p:sp></p:spTree>"#,
        gradient("5400000", "0")
    ))?;
    let background = &json["pages"][0]["elements"][0]["fillGradient"];
    let fill = &json["pages"][0]["elements"][1]["fillGradient"];
    near_array(&fill["points"], &[360.0, 0.0, 360.0, 405.0])?;
    near_array(&fill["transform"], &[0.0, -1.0, -1.0, 0.0, 50.0, 40.0])?;
    assert_eq!(fill["points"], background["points"]);
    assert!(background["transform"].is_null());
    Ok(())
}

fn gradient(angle: &str, scaled: &str) -> String {
    format!(
        r#"<a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs><a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs></a:gsLst><a:lin ang="{angle}" scaled="{scaled}"/></a:gradFill>"#
    )
}
