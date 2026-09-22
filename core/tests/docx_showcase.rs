pub mod support;

use jz_office_core::{
    model::{ElementType, LineSpacingRule},
    parse_reader,
};
use std::io::Cursor;
use support::{
    checks, docx_showcase,
    package::{archive, TestResult},
};

#[test]
fn showcase_contains_basic_content_and_paragraph_layout_cases() -> TestResult {
    let document = parse_reader(Cursor::new(archive(&docx_showcase::parts())?))?;
    let image = checks::element(&document.blocks, ElementType::IMAGE)?;
    checks::near(image.width, 72.0);
    checks::near(image.height, 36.0);
    let table = checks::element(&document.blocks, ElementType::TABLE)?;
    assert_eq!(table.rows.len(), 2);
    assert!(table.rows.iter().all(|row| row.len() == 2));
    assert_eq!(table.column_widths.len(), 2);
    checks::near(table.column_widths[0], 144.0);
    checks::near(table.column_widths[1], 216.0);
    let paragraphs: Vec<_> = document
        .blocks
        .iter()
        .flat_map(|block| &block.paragraphs)
        .collect();
    for (label, rule, spacing) in [
        ("单倍行距", LineSpacingRule::Auto, 1.0),
        ("1.5 倍行距", LineSpacingRule::Auto, 1.5),
        ("双倍行距", LineSpacingRule::Auto, 2.0),
        ("固定 20 pt 行距", LineSpacingRule::Exact, 20.0),
        ("最小 18 pt 行距", LineSpacingRule::AtLeast, 18.0),
    ] {
        let paragraph = paragraphs
            .iter()
            .find(|paragraph| paragraph.runs[0].text.starts_with(label))
            .ok_or("Missing paragraph layout case")?;
        assert_eq!(paragraph.line_spacing_rule, rule);
        checks::near(paragraph.line_spacing, spacing);
        assert_eq!(
            checks::text(std::slice::from_ref(*paragraph))
                .lines()
                .count(),
            2
        );
    }
    for first_line in [20.0, -16.0] {
        let paragraph = paragraphs
            .iter()
            .find(|paragraph| paragraph.first_line_indent == first_line)
            .ok_or("Missing indentation comparison")?;
        checks::near(paragraph.indent, 24.0);
        checks::near(paragraph.right_indent, 12.0);
        assert_eq!(
            checks::text(std::slice::from_ref(*paragraph))
                .lines()
                .count(),
            2
        );
    }
    let right = paragraphs
        .iter()
        .find(|paragraph| paragraph.alignment == 2)
        .ok_or("Missing right-aligned comparison")?;
    assert_eq!(
        checks::text(std::slice::from_ref(*right)).lines().count(),
        2
    );
    let styles = paragraphs
        .iter()
        .find(|paragraph| paragraph.runs.len() == 2 && paragraph.runs[1].underline)
        .ok_or("Missing inherited and direct text styles")?;
    assert_eq!(styles.alignment, 1);
    assert!(styles.runs[0].bold);
    assert!(!styles.runs[1].bold);
    assert!(styles.runs[1].italic);
    checks::near(styles.runs[0].size, 18.0);
    checks::near(styles.runs[1].size, 14.0);
    assert_eq!(styles.runs[0].color, 0xff336699);
    assert_eq!(styles.runs[1].color, 0xffc03020);
    assert!(paragraphs.iter().any(|paragraph| {
        let text = checks::text(std::slice::from_ref(*paragraph));
        text.contains('\t') && text.contains('\n')
    }));
    Ok(())
}

#[test]
fn showcase_preserves_explicit_inherited_and_missing_font_names() -> TestResult {
    let document = parse_reader(Cursor::new(archive(&docx_showcase::parts())?))?;
    let runs: Vec<_> = document
        .blocks
        .iter()
        .flat_map(|block| &block.paragraphs)
        .flat_map(|paragraph| &paragraph.runs)
        .collect();
    for (label, face, emphasized) in [
        ("直接指定等宽", "monospace", false),
        ("段落样式继承", "monospace", false),
        ("字符样式继承", "serif", true),
        ("直接覆盖字体", "monospace", true),
        ("缺失字体回退", "Unavailable Office Font", false),
    ] {
        let run = runs
            .iter()
            .find(|run| run.text.starts_with(label))
            .ok_or("Missing font showcase case")?;
        assert_eq!(run.font_face, face);
        assert_eq!(run.bold, emphasized);
        assert_eq!(run.italic, emphasized);
        assert!(run.text.contains("iiii WWWW 0123456789 中文"));
    }
    let original = runs
        .iter()
        .find(|run| run.text.starts_with("蓝色粗体"))
        .ok_or("Missing original text style sample")?;
    assert!(original.font_face.is_empty());
    assert!(document
        .warnings
        .iter()
        .any(|warning| warning.contains("device fonts")));
    Ok(())
}
