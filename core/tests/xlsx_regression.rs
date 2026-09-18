pub mod support;

use jz_office_core::parse_reader;
use serde_json::Value;
use std::io::Cursor;
use support::{
    package::{archive, replace, Parts, TestResult},
    xlsx,
};

fn parse(parts: &Parts) -> TestResult<Value> {
    Ok(serde_json::to_value(parse_reader(Cursor::new(archive(
        parts,
    )?))?)?)
}

fn cell(json: &Value, row: u32, column: u32) -> &Value {
    match json["sheets"][0]["cells"].as_array().and_then(|cells| {
        cells
            .iter()
            .find(|c| c["row"] == row && c["column"] == column)
    }) {
        Some(cell) => cell,
        None => panic!("Missing cell {row},{column}"),
    }
}

#[test]
fn workbook_uses_relationships_in_sheet_order_and_current_schema() -> TestResult {
    let json = parse(&xlsx::parts())?;
    assert_eq!(json["kind"], "XLSX");
    assert_eq!(json["schemaVersion"], 5);
    assert_eq!(json["sheets"][0]["name"], "Overview");
    assert_eq!(json["sheets"][1]["name"], "Data");
    assert_eq!(json["pages"], serde_json::json!([]));
    Ok(())
}

#[test]
fn types_formats_and_formula_caches_are_displayed() -> TestResult {
    let json = parse(&xlsx::parts())?;
    for (row, col, expected, numeric) in [
        (0, 0, "Quarterly Overview", false),
        (1, 1, "1,234.50", true),
        (2, 1, "12.50%", true),
        (3, 1, "2024-01-01", true),
        (4, 1, "2,469.00", true),
        (5, 1, "TRUE", false),
        (6, 1, "#DIV/0!", false),
        (7, 0, "Rich text", false),
        (7, 1, "12:00:00", true),
        (8, 1, "=SUM(B2:B3)", false),
    ] {
        assert_eq!(cell(&json, row, col)["text"], expected);
        assert_eq!(cell(&json, row, col)["numeric"], numeric);
    }
    assert_eq!(json["sheets"][1]["cells"][2]["text"], "ready");
    assert!(json["warnings"].as_array().is_some_and(|w| !w.is_empty()));
    Ok(())
}

#[test]
fn styles_merges_and_point_dimensions_survive_serialization() -> TestResult {
    let json = parse(&xlsx::parts())?;
    let style = &json["cellStyles"][1];
    assert_eq!(cell(&json, 0, 0)["style"], 1);
    for key in ["bold", "italic", "underline", "wrap"] {
        assert_eq!(style[key], true);
    }
    assert_eq!(style["fontSize"], 20.0);
    assert_eq!(style["color"], 0xffffffff_u32);
    assert_eq!(style["fill"], 0xff24745c_u32);
    assert_eq!(style["alignment"], 1);
    assert_eq!(style["verticalAlignment"], "CENTER");
    assert_eq!(style["borders"], serde_json::to_value([0xff112233_u32; 4])?);
    assert_eq!(
        json["sheets"][0]["merges"][0],
        serde_json::json!({"startRow":0,"startColumn":0,"endRow":0,"endColumn":3})
    );
    assert_eq!(json["sheets"][0]["rowHeights"][0], 36.0);
    assert_eq!(json["sheets"][0]["rowHeights"][1], 18.0);
    assert_eq!(
        json["sheets"][0]["rowHeights"].as_array().map(Vec::len),
        Some(120)
    );
    assert_eq!(
        json["sheets"][0]["columnWidths"].as_array().map(Vec::len),
        Some(12)
    );
    Ok(())
}

#[test]
fn dates_respect_1900_fake_leap_day_and_1904_epoch() -> TestResult {
    for (serial, system, expected) in [
        (59, false, "1900-02-28"),
        (60, false, "1900-02-29"),
        (61, false, "1900-03-01"),
        (0, true, "1904-01-01"),
        (1, true, "1904-01-02"),
    ] {
        let mut parts = xlsx::parts();
        replace(
            &mut parts,
            "xl/workbook.xml",
            &xlsx::WORKBOOK.replace(
                "date1904=\"0\"",
                if system {
                    "date1904=\"1\""
                } else {
                    "date1904=\"0\""
                },
            ),
        );
        replace(
            &mut parts,
            "xl/worksheets/sheet2.xml",
            &xlsx::sheet(&format!(
                r#"<sheetData><row r="1"><c r="A1" s="4"><v>{serial}</v></c></row></sheetData>"#
            )),
        );
        assert_eq!(cell(&parse(&parts)?, 0, 0)["text"], expected);
    }
    Ok(())
}

#[test]
fn sparse_extents_ignore_dimension_hints_and_preserve_hidden_sizes() -> TestResult {
    let mut parts = xlsx::parts();
    replace(
        &mut parts,
        "xl/worksheets/sheet2.xml",
        &xlsx::sheet(
            r#"<dimension ref="A1:XFD1048576"/><cols><col min="2" max="2" hidden="1"/></cols><sheetData><row r="10000"><c r="IV10000"><v>7</v></c><c r="A10000"><v>8</v></c></row><row r="2" hidden="1"/></sheetData>"#,
        ),
    );
    let json = parse(&parts)?;
    let sheet = &json["sheets"][0];
    assert_eq!(sheet["rowHeights"].as_array().map(Vec::len), Some(10000));
    assert_eq!(sheet["columnWidths"].as_array().map(Vec::len), Some(256));
    assert_eq!(sheet["rowHeights"][1], 0.0);
    assert_eq!(sheet["columnWidths"][1], 0.0);
    assert_eq!(sheet["cells"].as_array().map(Vec::len), Some(2));
    assert_eq!(sheet["cells"][0]["column"], 0);
    assert_eq!(sheet["cells"][1]["column"], 255);
    Ok(())
}

#[test]
fn empty_sheets_have_minimum_extent_and_merge_only_sheets_extend() -> TestResult {
    for (body, rows, cols) in [
        ("<sheetData/>", 1, 1),
        (r#"<mergeCells><mergeCell ref="B2:E4"/></mergeCells>"#, 4, 5),
    ] {
        let mut parts = xlsx::parts();
        replace(&mut parts, "xl/worksheets/sheet2.xml", &xlsx::sheet(body));
        let json = parse(&parts)?;
        assert_eq!(
            json["sheets"][0]["rowHeights"].as_array().map(Vec::len),
            Some(rows)
        );
        assert_eq!(
            json["sheets"][0]["columnWidths"].as_array().map(Vec::len),
            Some(cols)
        );
    }
    Ok(())
}
