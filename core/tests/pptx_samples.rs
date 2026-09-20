pub mod support;

use jz_office_core::{model::ElementType, parse_reader};
use std::{collections::HashSet, io::Cursor};
use support::{
    checks,
    package::{archive, TestResult},
    pptx_backgrounds, pptx_wrapping,
};

#[test]
fn new_samples_use_unique_non_visual_shape_ids_on_each_slide() -> TestResult {
    for (sample, parts) in [
        ("wrapping", pptx_wrapping::parts()),
        ("backgrounds", pptx_backgrounds::parts()),
    ] {
        for (path, bytes) in parts
            .iter()
            .filter(|(path, _)| path.starts_with("ppt/slides/") && path.ends_with(".xml"))
        {
            let slide = roxmltree::Document::parse(std::str::from_utf8(bytes)?)?;

            let mut ids = HashSet::new();
            for node in slide
                .descendants()
                .filter(|node| node.has_tag_name("cNvPr"))
            {
                let id: u32 = node.attribute("id").ok_or("shape ID missing")?.parse()?;
                assert!(
                    ids.insert(id),
                    "Duplicate shape ID {id} in {sample}: {path}"
                );
            }
            assert!(!ids.is_empty(), "No shape IDs in {sample}: {path}");
        }
    }
    Ok(())
}

#[test]
fn wrapping_sample_preserves_alignment_runs_and_each_break_mode() -> TestResult {
    let bytes = archive(&pptx_wrapping::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    assert_eq!(document.pages.len(), 9);
    for (index, page) in document.pages.iter().enumerate() {
        assert_eq!([page.width, page.height], [600.0, 300.0]);
        let text = checks::element(&page.elements, ElementType::TEXT)?;
        let paragraph = &text.paragraphs[0];
        assert_eq!(usize::from(paragraph.alignment), index / 3);
        assert_eq!(paragraph.runs.len(), 2);
        assert!(paragraph.runs.iter().all(|run| run.size == 60.0));
        assert_eq!(text.text_wrap, index % 3 == 2);
        assert_eq!(
            checks::text(&text.paragraphs),
            if index % 3 == 1 {
                "PART\n05"
            } else {
                "PART 05"
            }
        );
        assert_eq!([text.x, text.y, text.width], [200.0, 10.0, 150.0]);
    }
    Ok(())
}

#[test]
fn background_sample_resolves_inherited_gradient_image_and_solid_override() -> TestResult {
    let bytes = archive(&pptx_backgrounds::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    assert_eq!(document.pages.len(), 3);
    let first = &document.pages[0];
    assert_eq!([first.width, first.height], [320.0, 180.0]);
    let gradient = first.elements[0]
        .fill_gradient
        .as_ref()
        .ok_or("inherited gradient missing")?;
    assert_eq!(gradient.colors, [0xffff6a28, 0xfff43f12]);
    assert_eq!(gradient.positions, [0.0, 1.0]);
    assert_eq!(gradient.angle, 90.0);
    assert!(!gradient.scaled && !gradient.in_slide_space);
    let image = checks::element(&first.elements, ElementType::IMAGE)?;
    assert_eq!(
        image.image.as_deref(),
        Some("ppt/media/transparent-title.png")
    );
    checks::bounds(image, [140.0, 70.0, 40.0, 40.0]);
    let second = &document.pages[1];
    assert_eq!(second.background, 0xff111111);
    assert_eq!(second.elements[0].fill, 0xff2a9d8f);
    assert!(second
        .elements
        .iter()
        .all(|element| element.fill_gradient.is_none()));
    Ok(())
}

#[test]
fn background_sample_retains_slide_space_fill_through_rotation_flip_and_group_translation(
) -> TestResult {
    let bytes = archive(&pptx_backgrounds::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    let shapes = &document.pages[2].elements;
    let rotated = &shapes[2];
    checks::bounds(rotated, [30.0, 30.0, 60.0, 100.0]);
    assert_eq!(rotated.rotation, 90.0);
    let transformed = &shapes[3];
    assert!(transformed.flip_h);
    for (actual, expected) in transformed
        .transform
        .into_iter()
        .zip([0.0, 1.0, -1.0, 0.0, 275.0, 55.0])
    {
        checks::near(actual, expected);
    }
    for shape in [rotated, transformed] {
        let gradient = shape
            .fill_gradient
            .as_ref()
            .ok_or("shape background gradient missing")?;
        assert!(gradient.in_slide_space);
        assert_eq!(gradient.colors, [0xffff6a28, 0xfff43f12]);
    }
    Ok(())
}
