pub mod support;

use jz_office_core::{
    model::{ElementType, Kind},
    parse_reader, Package,
};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, TestResult, PNG},
    pptx,
};

#[test]
fn presentation_slide_list_controls_order_instead_of_zip_or_filename_order() -> TestResult {
    let bytes = archive(&pptx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    assert!(matches!(document.kind, Kind::PPTX));
    assert_eq!(document.pages.len(), 2);
    let first = checks::element(&document.pages[0].elements, ElementType::TEXT)?;
    let second = checks::element(&document.pages[1].elements, ElementType::TEXT)?;
    assert_eq!(checks::text(&first.paragraphs), "First \u{4e2d}\u{6587}");
    assert_eq!(checks::text(&second.paragraphs), "Second English");
    assert_eq!(document.pages[0].background, 0xfff4f7fa);
    assert_eq!(document.pages[1].background, 0xff112233);
    for page in &document.pages {
        checks::near(page.width, 720.0);
        checks::near(page.height, 405.0);
    }
    checks::near(document.width, 720.0);
    assert!(document.blocks.is_empty());
    Ok(())
}

#[test]
fn absolute_shapes_and_text_convert_emu_to_points() -> TestResult {
    let bytes = archive(&pptx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    let elements = &document.pages[0].elements;
    let text = checks::element(elements, ElementType::TEXT)?;
    checks::bounds(text, [36.0, 24.0, 500.0, 54.0]);
    checks::near(text.paragraphs[0].runs[0].size, 28.0);
    assert!(text.paragraphs[0].runs[0].bold);
    assert_eq!(text.paragraphs[0].runs[0].color, 0xff112233);
    let rectangle = checks::element(elements, ElementType::RECT)?;
    checks::bounds(rectangle, [36.0, 112.0, 180.0, 60.0]);
    assert_eq!(rectangle.fill, 0xffdcfce7);
    assert_eq!(rectangle.stroke, 0xff228855);
    checks::near(rectangle.stroke_width, 2.0);
    let ellipse = checks::element(elements, ElementType::ELLIPSE)?;
    checks::bounds(ellipse, [240.0, 112.0, 80.0, 50.0]);
    assert_eq!(ellipse.fill, 0xffffe3a3);
    let line = checks::element(elements, ElementType::LINE)?;
    checks::bounds(line, [36.0, 190.0, 284.0, 0.0]);
    assert_eq!(line.stroke, 0xff475569);
    checks::near(line.stroke_width, 2.0);
    Ok(())
}

#[test]
fn pptx_image_resolves_parent_segments_to_embedded_png() -> TestResult {
    let bytes = archive(&pptx::parts())?;

    let document = parse_reader(Cursor::new(bytes.clone()))?;

    let image = checks::element(&document.pages[0].elements, ElementType::IMAGE)?;
    assert_eq!(image.image.as_deref(), Some("ppt/media/pixel.png"));
    checks::bounds(image, [400.0, 112.0, 72.0, 36.0]);
    let mut package = Package::new(Cursor::new(bytes))?;
    assert_eq!(package.read("ppt/media/pixel.png", 1024)?, PNG);
    Ok(())
}

#[test]
fn pptx_table_retains_absolute_bounds_and_rectangular_cells() -> TestResult {
    let bytes = archive(&pptx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    let table = checks::element(&document.pages[0].elements, ElementType::TABLE)?;
    checks::bounds(table, [36.0, 230.0, 360.0, 72.0]);
    assert_eq!(table.column_widths.len(), 2);
    checks::near(table.column_widths[0], 144.0);
    checks::near(table.column_widths[1], 216.0);
    assert_eq!(table.rows.len(), 2);
    assert!(table.rows.iter().all(|row| row.len() == 2));
    assert_eq!(checks::text(&table.rows[0][0]), "Language");
    assert_eq!(checks::text(&table.rows[0][1]), "Greeting");
    assert_eq!(checks::text(&table.rows[1][0]), "\u{4e2d}\u{6587}");
    assert_eq!(checks::text(&table.rows[1][1]), "Hello \u{4f60}\u{597d}");
    Ok(())
}
