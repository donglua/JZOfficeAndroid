pub mod support;

use jz_office_core::{parse_reader, Error};
use std::io::Cursor;
use support::{
    package::{archive, replace, Parts, TestResult},
    xlsx,
};

fn fixture(body: &str) -> Parts {
    let mut parts = xlsx::parts();
    replace(&mut parts, "xl/worksheets/sheet2.xml", &xlsx::sheet(body));
    parts
}

#[test]
fn style_table_accepts_2048_and_rejects_2049() -> TestResult {
    for count in [2048, 2049] {
        let mut parts = fixture("<sheetData/>");
        replace(
            &mut parts,
            "xl/worksheets/sheet1.xml",
            &xlsx::sheet("<sheetData/>"),
        );
        replace(
            &mut parts,
            "xl/styles.xml",
            &format!(
                "<styleSheet><cellXfs>{}</cellXfs></styleSheet>",
                "<xf/>".repeat(count)
            ),
        );
        let result = parse_reader(Cursor::new(archive(&parts)?));
        if count == 2048 {
            assert_eq!(result?.cell_styles.len(), 2048);
        } else {
            assert!(matches!(result, Err(Error::Limit(_))));
        }
    }
    Ok(())
}

fn workbook(sheets: &str) -> String {
    format!(
        r#"<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>{sheets}</sheets></workbook>"#
    )
}

#[test]
fn sheet_budget_includes_hidden_sheets() -> TestResult {
    for count in [32, 33] {
        let mut parts = fixture("<sheetData/>");
        let sheets = (0..count)
            .map(|i| {
                format!(
                    r#"<sheet name="S{i}" sheetId="{i}" r:id="second" state="{}"/>"#,
                    if i == 0 { "visible" } else { "hidden" }
                )
            })
            .collect::<String>();
        replace(&mut parts, "xl/workbook.xml", &workbook(&sheets));
        let result = parse_reader(Cursor::new(archive(&parts)?));
        if count == 32 {
            assert_eq!(result?.sheets.len(), 1);
        } else {
            assert!(matches!(result, Err(Error::Limit(_))));
        }
    }
    Ok(())
}

#[test]
fn merge_budget_is_workbook_wide() -> TestResult {
    let merges = (1..=1000)
        .map(|row| format!(r#"<mergeCell ref="A{row}:B{row}"/>"#))
        .collect::<String>();
    for extra in [false, true] {
        let mut parts = fixture(&format!("<mergeCells>{merges}</mergeCells>"));
        replace(
            &mut parts,
            "xl/worksheets/sheet1.xml",
            &xlsx::sheet(if extra {
                r#"<mergeCells><mergeCell ref="A1:B1"/></mergeCells>"#
            } else {
                "<sheetData/>"
            }),
        );
        let result = parse_reader(Cursor::new(archive(&parts)?));
        if extra {
            assert!(matches!(result, Err(Error::Limit(_))));
        } else {
            assert_eq!(result?.sheets[0].merges.len(), 1000);
        }
    }
    Ok(())
}

#[test]
fn stored_cell_budget_is_workbook_wide() -> TestResult {
    let rows = (1..=10000)
        .map(|row| format!(r#"<row r="{row}"><c r="A{row}"><v>1</v></c></row>"#))
        .collect::<String>();
    let sheets = (0..5)
        .map(|i| format!(r#"<sheet name="S{i}" sheetId="{i}" r:id="first"/>"#))
        .collect::<String>();
    for extra in [false, true] {
        let mut parts = fixture("<sheetData><row><c><v>2</v></c></row></sheetData>");
        let extra_sheet = if extra {
            r#"<sheet name="Extra" sheetId="5" r:id="second"/>"#
        } else {
            ""
        };
        replace(
            &mut parts,
            "xl/workbook.xml",
            &workbook(&format!("{sheets}{extra_sheet}")),
        );
        replace(
            &mut parts,
            "xl/worksheets/sheet1.xml",
            &xlsx::sheet(&format!("<sheetData>{rows}</sheetData>")),
        );
        let result = parse_reader(Cursor::new(archive(&parts)?));
        if extra {
            assert!(matches!(
                result,
                Err(Error::Limit("XLSX exceeds 50000 stored cells"))
            ))
        } else {
            assert_eq!(
                result?.sheets.iter().map(|s| s.cells.len()).sum::<usize>(),
                50000
            );
        }
    }
    Ok(())
}

#[test]
fn shared_string_expansion_obeys_text_budget() -> TestResult {
    let mut parts = fixture(&format!(
        "<sheetData><row>{}</row></sheetData>",
        r#"<c t="s"><v>0</v></c>"#.repeat(9)
    ));
    replace(
        &mut parts,
        "xl/sharedStrings.xml",
        &format!("<sst><si><t>{}</t></si></sst>", "x".repeat(1024 * 1024)),
    );
    assert!(matches!(
        parse_reader(Cursor::new(archive(&parts)?)),
        Err(Error::Limit("XLSX text exceeds 8 MiB"))
    ));
    Ok(())
}
