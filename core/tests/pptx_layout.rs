pub mod support;

use jz_office_core::{
    model::{Document, Element, ElementType, VerticalAlignment},
    parse_reader,
};
use std::io::{self, Cursor};
use support::{
    checks,
    package::{archive, TestResult},
    pptx,
};

const CROP_WARNING: &str = "PPTX pictures with invalid crop bounds use the full image.";

struct TextShape<'a> {
    id: u32,
    placeholder_type: &'a str,
    placeholder_index: &'a str,
    anchor: Option<&'a str>,
    y: u32,
    text: &'a str,
}

struct InheritedShape<'a> {
    id: u32,
    placeholder_type: &'a str,
    placeholder_index: &'a str,
    anchor: Option<&'a str>,
}

#[test]
fn pptx_text_anchor_inherits_from_layout_and_master_when_direct_shape_omits_anchor() -> TestResult {
    let mut parts = pptx::parts();
    support::package::replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &slide(&format!(
            "{}{}{}",
            text_shape(TextShape {
                id: 20,
                placeholder_type: "title",
                placeholder_index: "1",
                anchor: None,
                y: 304_800,
                text: "Layout inherited",
            }),
            text_shape(TextShape {
                id: 21,
                placeholder_type: "body",
                placeholder_index: "2",
                anchor: Some("t"),
                y: 914_400,
                text: "Top reset",
            }),
            text_shape(TextShape {
                id: 22,
                placeholder_type: "subTitle",
                placeholder_index: "3",
                anchor: None,
                y: 1_524_000,
                text: "Master inherited",
            })
        )),
    );
    support::package::replace(
        &mut parts,
        "ppt/slideLayouts/slideLayout1.xml",
        &layout(&format!(
            "{}{}{}",
            inherited_shape(InheritedShape {
                id: 120,
                placeholder_type: "title",
                placeholder_index: "1",
                anchor: Some("b"),
            }),
            inherited_shape(InheritedShape {
                id: 121,
                placeholder_type: "body",
                placeholder_index: "2",
                anchor: Some("b"),
            }),
            inherited_shape(InheritedShape {
                id: 122,
                placeholder_type: "subTitle",
                placeholder_index: "3",
                anchor: None,
            })
        )),
    );
    support::package::replace(
        &mut parts,
        "ppt/slideMasters/slideMaster1.xml",
        &master(&format!(
            "{}{}{}",
            inherited_shape(InheritedShape {
                id: 220,
                placeholder_type: "title",
                placeholder_index: "1",
                anchor: Some("ctr"),
            }),
            inherited_shape(InheritedShape {
                id: 221,
                placeholder_type: "body",
                placeholder_index: "2",
                anchor: Some("ctr"),
            }),
            inherited_shape(InheritedShape {
                id: 222,
                placeholder_type: "subTitle",
                placeholder_index: "3",
                anchor: Some("ctr"),
            })
        )),
    );

    let document = parse_reader(Cursor::new(archive(&parts)?))?;

    let page = &document.pages[0];
    assert_eq!(
        text_element(page.elements.as_slice(), "Layout inherited")?.vertical_alignment,
        VerticalAlignment::Bottom
    );
    assert_eq!(
        text_element(page.elements.as_slice(), "Top reset")?.vertical_alignment,
        VerticalAlignment::Top
    );
    assert_eq!(
        text_element(page.elements.as_slice(), "Master inherited")?.vertical_alignment,
        VerticalAlignment::Center
    );
    Ok(())
}

#[test]
fn pptx_picture_crop_is_absent_without_source_rectangle() -> TestResult {
    let document = parse_reader(Cursor::new(archive(&pptx::parts())?))?;

    let image = checks::element(&document.pages[0].elements, ElementType::IMAGE)?;
    assert!(image.image_crop.is_none());
    Ok(())
}

#[test]
fn pptx_picture_crop_parses_integer_percent_and_missing_edges() -> TestResult {
    let document = document_with_src_rect(r#"<a:srcRect l="10000" t="12.5%" r="25000"/>"#)?;

    let image = checks::element(&document.pages[0].elements, ElementType::IMAGE)?;
    let crop = image.image_crop.ok_or("missing crop")?;
    checks::near(crop.left, 0.1);
    checks::near(crop.top, 0.125);
    checks::near(crop.right, 0.25);
    checks::near(crop.bottom, 0.0);
    assert!(!document
        .warnings
        .iter()
        .any(|warning| warning == CROP_WARNING));
    Ok(())
}

#[test]
fn pptx_picture_crop_uses_whole_image_with_warning_for_invalid_rectangles() -> TestResult {
    for src_rect in [
        r#"<a:srcRect l="-1"/>"#,
        r#"<a:srcRect l="NaN"/>"#,
        r#"<a:srcRect l="60000" r="40000"/>"#,
        r#"<a:srcRect l="99950"/>"#,
    ] {
        let document = document_with_src_rect(src_rect)?;
        let image = checks::element(&document.pages[0].elements, ElementType::IMAGE)?;
        assert!(
            image.image_crop.is_none(),
            "crop should be ignored for {src_rect}"
        );
        assert!(
            document
                .warnings
                .iter()
                .any(|warning| warning == CROP_WARNING),
            "crop warning missing for {src_rect}"
        );
    }
    Ok(())
}

fn document_with_src_rect(src_rect: &str) -> TestResult<Document> {
    let mut parts = pptx::parts();
    let slide = part_text(&parts, "ppt/slides/slide2.xml")?.replace(
        r#"<p:blipFill><a:blip r:embed="rImage"/>"#,
        &format!(r#"<p:blipFill><a:blip r:embed="rImage"/>{src_rect}"#),
    );
    support::package::replace(&mut parts, "ppt/slides/slide2.xml", &slide);
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

fn text_element<'a>(elements: &'a [Element], expected: &str) -> TestResult<&'a Element> {
    elements
        .iter()
        .filter(|element| matches!(element.kind, ElementType::TEXT))
        .find(|element| checks::text(&element.paragraphs) == expected)
        .ok_or_else(|| io::Error::other(format!("missing text element {expected:?}")).into())
}

fn part_text(parts: &[(&'static str, Vec<u8>)], name: &str) -> TestResult<String> {
    let bytes = parts
        .iter()
        .find(|(path, _)| *path == name)
        .ok_or_else(|| io::Error::other(format!("missing fixture part {name}")))?
        .1
        .clone();
    Ok(String::from_utf8(bytes)?)
}

fn slide(content: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld><p:spTree>{}{content}</p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"#,
        group_shape()
    )
}

fn layout(content: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank" preserve="1">
<p:cSld name="Blank"><p:spTree>{}{content}</p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"#,
        group_shape()
    )
}

fn master(content: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld name="Fixture master"><p:spTree>{}{content}</p:spTree></p:cSld>
<p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4"
accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" tx1="dk1" tx2="dk2" hlink="hlink" folHlink="folHlink"/>
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rLayout"/></p:sldLayoutIdLst>
<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"#,
        group_shape()
    )
}

fn text_shape(shape: TextShape<'_>) -> String {
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="{}"/><p:cNvSpPr txBox="1"/>
<p:nvPr><p:ph type="{}" idx="{}"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="{}"/><a:ext cx="3657600" cy="457200"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>
<p:txBody>{}<a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="1600"/><a:t>{}</a:t></a:r></a:p></p:txBody></p:sp>"#,
        shape.id,
        shape.text,
        shape.placeholder_type,
        shape.placeholder_index,
        shape.y,
        body_properties(shape.anchor),
        shape.text
    )
}

fn inherited_shape(shape: InheritedShape<'_>) -> String {
    format!(
        r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="Placeholder {}"/><p:cNvSpPr txBox="1"/>
<p:nvPr><p:ph type="{}" idx="{}"/></p:nvPr></p:nvSpPr>
<p:txBody>{}<a:lstStyle/><a:p/></p:txBody></p:sp>"#,
        shape.id,
        shape.id,
        shape.placeholder_type,
        shape.placeholder_index,
        body_properties(shape.anchor)
    )
}

fn body_properties(anchor: Option<&str>) -> String {
    match anchor {
        Some(value) => format!(r#"<a:bodyPr anchor="{value}"/>"#),
        None => String::from("<a:bodyPr/>"),
    }
}

fn group_shape() -> &'static str {
    r#"<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"#
}
