pub mod support;

use jz_office_core::{
    model::{Document, ElementType},
    parse_reader,
};
use std::io::Cursor;
use support::{
    package::{archive, replace, TestResult},
    pptx,
};

const RECT: &str = r#"<a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="0"/></a:lnTo><a:lnTo><a:pt x="100" y="50"/></a:lnTo><a:close/>"#;
const GRADIENT: &str = r#"<a:gradFill><a:gsLst><a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs><a:gs pos="0"><a:srgbClr val="FF0000"><a:alpha val="50000"/></a:srgbClr></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>"#;

fn document(properties: &str) -> TestResult<Document> {
    let mut parts = pptx::parts();
    replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:spPr><a:xfrm><a:off x="127000" y="254000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm>{properties}</p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:t>Retained</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"#
        ),
    );
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

fn geometry(commands: &str) -> String {
    format!(
        r#"<a:custGeom><a:pathLst><a:path w="100" h="50">{commands}</a:path></a:pathLst></a:custGeom><a:solidFill><a:srgbClr val="112233"/></a:solidFill>"#
    )
}

#[test]
fn path_points_scale_to_shape_and_subpaths_preserve_winding_order() -> TestResult {
    let doc = document(&geometry(&format!("{RECT}{RECT}")))?;
    let json = serde_json::to_value(&doc.pages[0].elements[0])?;
    assert_eq!(json["type"], "PATH");
    assert_eq!(
        json["paths"][0]["commands"][1]["points"],
        serde_json::json!([200.0, 0.0])
    );
    assert_eq!(
        json["paths"][0]["commands"][2]["points"],
        serde_json::json!([200.0, 100.0])
    );
    assert_eq!(json["paths"][0]["commands"][4]["op"], "MOVE");
    assert_eq!(
        json["paths"][0]["commands"]
            .as_array()
            .ok_or("commands missing")?
            .len(),
        8
    );
    Ok(())
}

#[test]
fn malformed_or_unsupported_path_is_omitted_whole_while_text_remains() -> TestResult {
    for commands in [
        format!("{RECT}<a:arcTo wR=\"20\" hR=\"20\" stAng=\"0\" swAng=\"5400000\"/>"),
        RECT.replace("x=\"100\"", "x=\"NaN\""),
        RECT.replace("x=\"100\"", "x=\"99999999999\""),
        RECT.replace("x=\"100\"", "x=\"adj1\""),
        "<a:lnTo><a:pt x=\"5\" y=\"5\"/></a:lnTo>".to_owned(),
    ] {
        let doc = document(&geometry(&commands))?;
        assert!(
            doc.pages[0]
                .elements
                .iter()
                .all(|e| !matches!(e.kind, ElementType::PATH)),
            "{commands}"
        );
        assert!(doc.pages[0]
            .elements
            .iter()
            .any(|e| matches!(e.kind, ElementType::TEXT)));
        assert!(!doc.warnings.is_empty());
    }
    Ok(())
}

#[test]
fn unsupported_path_fill_modes_are_not_silently_painted_as_normal() -> TestResult {
    let doc = document(&geometry(RECT).replace("w=\"100\"", "fill=\"lighten\" w=\"100\""))?;
    assert!(doc.pages[0]
        .elements
        .iter()
        .all(|e| !matches!(e.kind, ElementType::PATH)));
    Ok(())
}

#[test]
fn gradient_only_shape_with_text_is_emitted_and_preserves_stop_alpha() -> TestResult {
    let doc = document(&format!("<a:prstGeom prst=\"rect\"/>{GRADIENT}"))?;
    assert_eq!(doc.pages[0].elements.len(), 2);
    let fill = doc.pages[0].elements[0]
        .fill_gradient
        .as_ref()
        .ok_or("gradient missing")?;
    assert_eq!(fill.colors, vec![0x80ff0000, 0xff0000ff]);
    assert_eq!(fill.positions, vec![0.0, 1.0]);
    assert_eq!(fill.angle, 90.0);
    assert!(!fill.scaled);
    assert!(!doc.warnings.iter().any(|w| w.contains("gradient, pattern")));
    Ok(())
}

#[test]
fn unsupported_or_invalid_gradients_do_not_create_invalid_model_data() -> TestResult {
    for fill in [
        GRADIENT.replace("5400000", "NaN"),
        GRADIENT.replace("5400000", "inf"),
        GRADIENT.replace(
            "<a:lin ang=\"5400000\" scaled=\"0\"/>",
            "<a:path path=\"circle\"/>",
        ),
        GRADIENT.replace("pos=\"0\"", "pos=\"NaN\""),
    ] {
        let doc = document(&format!(
            "{fill}<a:ln><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln>"
        ))?;
        assert!(doc.pages[0]
            .elements
            .iter()
            .all(|e| e.fill_gradient.is_none()));
        assert!(!doc.warnings.is_empty());
    }
    Ok(())
}
