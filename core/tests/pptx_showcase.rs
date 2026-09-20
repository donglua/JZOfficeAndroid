pub mod support;

use jz_office_core::{
    model::{Document, ElementType},
    parse_reader,
};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, TestResult},
    pptx, pptx_backgrounds, pptx_charts, pptx_colors, pptx_compat, pptx_showcase, pptx_typography,
    pptx_wrapping, pptx_wrapping_showcase,
};

#[test]
fn showcase_preserves_all_source_slide_content_in_chapter_order() -> TestResult {
    let parts = pptx_showcase::parts()?;

    let document = parse_reader(Cursor::new(archive(&parts)?))?;

    assert_eq!(document.pages.len(), 16);
    assert_eq!(document.width, 960.0);
    assert!(document
        .pages
        .iter()
        .all(|page| [page.width, page.height] == [960.0, 540.0]));
    let index = checks::text(&document.pages[0].elements[0].paragraphs);
    for range in ["02-03", "04", "05-06", "07-08", "09-10", "11-13", "14-16"] {
        assert!(index.contains(range), "Missing chapter page range {range}");
    }
    let mut page_index = 1;
    for (parts, count, annotated) in [
        (pptx::parts(), 2, true),
        (pptx_compat::parts(), 1, true),
        (pptx_charts::parts(), 2, true),
        (pptx_colors::parts(), 2, true),
        (pptx_typography::parts(), 2, true),
        (pptx_wrapping_showcase::parts()?, 3, false),
        (pptx_backgrounds::parts(), 3, true),
    ] {
        let source = parse_reader(Cursor::new(archive(&parts)?))?;
        for page in source.pages.into_iter().take(count) {
            let (width, height) = if annotated {
                (664.0, 373.5)
            } else {
                (960.0, 540.0)
            };
            let scale = (width / page.width).min(height / page.height);
            let combined = &document.pages[page_index];
            assert_eq!(
                page.elements.len() + if annotated { 5 } else { 0 },
                combined.elements.len(),
                "Elements on page {}",
                page_index + 1
            );
            for (original, transformed) in page.elements.iter().zip(&combined.elements) {
                assert_eq!(
                    std::mem::discriminant(&original.kind),
                    std::mem::discriminant(&transformed.kind)
                );
                assert_eq!(
                    checks::text(&original.paragraphs),
                    checks::text(&transformed.paragraphs)
                );
                assert_eq!(original.fill, transformed.fill);
                assert_eq!(original.text_wrap, transformed.text_wrap);
                assert_eq!(original.cell_fills, transformed.cell_fills);
                assert_eq!(original.rows.len(), transformed.rows.len());
                for (original, transformed) in original
                    .paragraphs
                    .iter()
                    .flat_map(|paragraph| &paragraph.runs)
                    .zip(
                        transformed
                            .paragraphs
                            .iter()
                            .flat_map(|paragraph| &paragraph.runs),
                    )
                {
                    assert!(
                        (original.size * scale - transformed.size).abs() < 0.01,
                        "Font size changed on page {}: {} -> {}",
                        page_index + 1,
                        original.size,
                        transformed.size
                    );
                    assert_eq!(original.color, transformed.color);
                    assert_eq!(original.font_face, transformed.font_face);
                }
                for (original, transformed) in original
                    .rows
                    .iter()
                    .flatten()
                    .zip(transformed.rows.iter().flatten())
                {
                    assert_eq!(checks::text(original), checks::text(transformed));
                }
            }
            page_index += 1;
        }
    }
    assert_eq!(page_index, 16);
    Ok(())
}

#[test]
fn showcase_scales_text_and_geometry_uniformly() -> TestResult {
    let document = showcase()?;

    let title = &document.pages[6].elements[0];
    let number = &document.pages[8].elements[0];
    let image = checks::element(&document.pages[13].elements, ElementType::IMAGE)?;

    checks::near(title.width, 520.0 * 83.0 / 90.0);
    checks::near(title.height, 140.0 * 83.0 / 90.0);
    assert!((title.paragraphs[0].runs[0].size - 64.0 * 83.0 / 90.0).abs() < 0.01);
    assert_eq!(title.paragraphs[0].runs[0].color, 0xfffff3dd);
    assert_eq!(number.paragraphs[0].runs[0].font_face, "Impact");
    assert_eq!(number.paragraphs[0].runs[0].size, 24.9);
    assert_eq!([image.width, image.height], [83.0, 83.0]);
    checks::near(image.transform[4], 314.5);
    checks::near(image.transform[5], 233.25);
    assert_eq!(
        image.image.as_deref(),
        Some("ppt/showcase/backgrounds/media/transparent-title.png")
    );
    for index in [13, 15] {
        let gradient = document.pages[index].elements[0]
            .fill_gradient
            .as_ref()
            .ok_or("gradient missing")?;
        assert_eq!(gradient.colors, [0xffff6a28, 0xfff43f12]);
        assert_eq!(gradient.angle, 90.0);
    }
    assert!(document.pages[15].elements[2..4].iter().all(|shape| shape
        .fill_gradient
        .as_ref()
        .is_some_and(|fill| fill.in_slide_space)));
    Ok(())
}

#[test]
fn showcase_retains_cached_chart_series_and_categories() -> TestResult {
    let document = showcase()?;

    for (index, labels) in [
        (4, vec!["Q1", "Q2", "Q3", "Q4", "Alpha", "Beta"]),
        (5, vec!["2024-01-01", "2024-01-04", "Daily"]),
    ] {
        let text: String = document.pages[index]
            .elements
            .iter()
            .map(|element| checks::text(&element.paragraphs))
            .collect();
        for label in labels {
            assert!(text.contains(label), "Missing chart label {label}");
        }
        let red = pptx_charts::data_segments(&document.pages[index], pptx_charts::RED);
        assert_eq!(red.len(), 3);
    }
    Ok(())
}

#[test]
fn three_comparison_pages_keep_all_nine_wrapping_conditions() -> TestResult {
    let source = parse_reader(Cursor::new(archive(&pptx_wrapping::parts())?))?;
    let document = showcase()?;

    for (alignment, page) in document.pages[10..13].iter().enumerate() {
        let examples: Vec<_> = page
            .elements
            .iter()
            .filter(|element| {
                element
                    .paragraphs
                    .first()
                    .is_some_and(|paragraph| paragraph.runs.len() == 2)
            })
            .collect();
        assert_eq!(examples.len(), 3);
        for (column, example) in examples.into_iter().enumerate() {
            let original = &source.pages[alignment * 3 + column].elements[0];
            assert_eq!(example.text_wrap, original.text_wrap);
            assert_eq!(
                checks::text(&example.paragraphs),
                checks::text(&original.paragraphs)
            );
            assert_eq!(
                example.paragraphs[0].alignment,
                original.paragraphs[0].alignment
            );
            assert!(example.paragraphs[0]
                .runs
                .iter()
                .all(|run| run.size == 48.0));
            assert_eq!([example.width, example.height], [120.0, 144.0]);
            assert!(
                page.elements.iter().any(|element| {
                    matches!(element.kind, ElementType::RECT)
                        && element.width == example.width
                        && element.height == example.height
                        && element.transform == example.transform
                }),
                "Each example must have a visible text-box boundary"
            );
        }
    }
    Ok(())
}

fn showcase() -> TestResult<Document> {
    Ok(parse_reader(Cursor::new(archive(
        &pptx_showcase::parts()?
    )?))?)
}
