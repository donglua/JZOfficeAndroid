pub mod support;

use jz_office_core::{model::ElementType, parse_reader, Package};
use std::io::Cursor;
use support::{
    checks, docx,
    package::{archive, replace, TestResult, PNG},
};

#[test]
fn direct_bold_off_overrides_inherited_style_when_parsing_docx() -> TestResult {
    let bytes = archive(&docx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    let title = &document.blocks[0].paragraphs[0];
    assert_eq!(title.alignment, 1);
    checks::near(title.before, 12.0);
    checks::near(title.after, 8.0);
    assert_eq!(title.runs.len(), 2);
    let inherited = &title.runs[0];
    assert_eq!(inherited.text, "\u{4e2d}\u{6587} Office");
    assert!(inherited.bold);
    assert!(!inherited.italic);
    assert!(!inherited.underline);
    checks::near(inherited.size, 18.0);
    assert_eq!(inherited.color, 0xff336699);
    let direct = &title.runs[1];
    assert_eq!(direct.text, " English regular");
    assert!(!direct.bold);
    assert!(direct.italic);
    assert!(direct.underline);
    checks::near(direct.size, 14.0);
    assert_eq!(direct.color, 0xffc03020);
    Ok(())
}

#[test]
fn explicit_false_spellings_override_bold_when_inherited() -> TestResult {
    for value in ["0", "false", "off"] {
        let mut parts = docx::parts();
        replace(
            &mut parts,
            "word/document.xml",
            &docx::DOCUMENT.replace(r#"<w:b w:val="0"/>"#, &format!(r#"<w:b w:val="{value}"/>"#)),
        );

        let document = parse_reader(Cursor::new(archive(&parts)?))?;

        assert!(document.blocks[0].paragraphs[0].runs[0].bold);
        assert!(!document.blocks[0].paragraphs[0].runs[1].bold, "{value}");
    }
    Ok(())
}

#[test]
fn tabs_line_breaks_and_plain_defaults_survive_docx_flow() -> TestResult {
    let bytes = archive(&docx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    checks::near(document.width, 612.0);
    let plain = &document.blocks[1].paragraphs;
    assert_eq!(
        checks::text(plain),
        "Plain paragraph with italic\ttab\nnext line"
    );
    checks::near(plain[0].runs[0].size, 11.0);
    assert!(!plain[0].runs[0].bold);
    assert!(plain[0].runs[1].italic);
    Ok(())
}

#[test]
fn rectangular_docx_table_preserves_cell_order_and_column_points() -> TestResult {
    let bytes = archive(&docx::parts())?;

    let document = parse_reader(Cursor::new(bytes))?;

    let table = checks::element(&document.blocks, ElementType::TABLE)?;
    assert_eq!(table.rows.len(), 2);
    assert!(table.rows.iter().all(|row| row.len() == 2));
    assert_eq!(table.column_widths.len(), 2);
    checks::near(table.column_widths[0], 144.0);
    checks::near(table.column_widths[1], 216.0);
    checks::near(table.width, 360.0);
    assert_eq!(checks::text(&table.rows[0][0]), "Language");
    assert_eq!(checks::text(&table.rows[0][1]), "Greeting");
    assert_eq!(checks::text(&table.rows[1][0]), "\u{4e2d}\u{6587}");
    assert_eq!(checks::text(&table.rows[1][1]), "Hello \u{4f60}\u{597d}");
    Ok(())
}

#[test]
fn docx_image_uses_normalized_relative_package_path() -> TestResult {
    let bytes = archive(&docx::parts())?;

    let document = parse_reader(Cursor::new(bytes.clone()))?;

    let image = checks::element(&document.blocks, ElementType::IMAGE)?;
    assert_eq!(image.image.as_deref(), Some("word/media/pixel.png"));
    checks::near(image.width, 72.0);
    checks::near(image.height, 36.0);
    let mut package = Package::new(Cursor::new(bytes))?;
    assert_eq!(package.read("word/media/pixel.png", 1024)?, PNG);
    Ok(())
}

#[test]
fn optional_header_adds_warning_without_entering_document_flow() -> TestResult {
    let mut baseline = docx::parts();
    replace(
        &mut baseline,
        "word/document.xml",
        &docx::DOCUMENT.replace(docx::HEADER_REFERENCE, ""),
    );
    let baseline = parse_reader(Cursor::new(archive(&baseline)?))?;

    let document = parse_reader(Cursor::new(archive(&docx::parts())?))?;

    assert_eq!(document.warnings.len(), baseline.warnings.len() + 1);
    assert_eq!(
        serde_json::to_value(&document.blocks)?,
        serde_json::to_value(&baseline.blocks)?
    );
    assert!(document
        .blocks
        .iter()
        .all(|block| !checks::text(&block.paragraphs).contains("Optional header")));
    Ok(())
}
