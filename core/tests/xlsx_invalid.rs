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
fn rejects_malformed_coordinates_and_duplicate_cells() -> TestResult {
    for reference in [
        "A0", "A01", "1A", "a1", "$A$1", "A", "1", "A-1", "A1:B2", "A2", "A1!", "A1 A2",
    ] {
        let parts = fixture(&format!(
            r#"<sheetData><row r="1"><c r="{reference}"><v>1</v></c></row></sheetData>"#
        ));
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&parts)?)),
                Err(Error::Invalid(_))
            ),
            "{reference}"
        );
    }
    for body in [
        r#"<sheetData><row r="1"><c r="A1"/><c r="A1"/></row></sheetData>"#,
        r#"<sheetData><row r="1"/><row r="1"/></sheetData>"#,
    ] {
        assert!(matches!(
            parse_reader(Cursor::new(archive(&fixture(body))?)),
            Err(Error::Invalid(_))
        ));
    }
    Ok(())
}

#[test]
fn rejects_out_of_range_coordinates_and_dimensions() -> TestResult {
    for body in [
        r#"<sheetData><row r="10001"/></sheetData>"#,
        r#"<sheetData><row r="1"><c r="IW1"/></row></sheetData>"#,
        r#"<cols><col min="1" max="257"/></cols>"#,
        r#"<mergeCells><mergeCell ref="A1:A10001"/></mergeCells>"#,
    ] {
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&fixture(body))?)),
                Err(Error::Limit(_))
            ),
            "{body}"
        );
    }
    for body in [
        r#"<cols><col min="3" max="2"/></cols>"#,
        r#"<sheetData><row r="1" ht="NaN"/></sheetData>"#,
        r#"<cols><col min="1" max="2" width="-1"/></cols>"#,
    ] {
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&fixture(body))?)),
                Err(Error::Invalid(_))
            ),
            "{body}"
        );
    }
    Ok(())
}

#[test]
fn rejects_invalid_style_ids_shared_strings_and_numeric_values() -> TestResult {
    for cell in [
        r#"<c r="A1" s="999"/>"#,
        r#"<c r="A1" s="-1"/>"#,
        r#"<c r="A1" s="x"/>"#,
        r#"<c r="A1" t="s"><v>999</v></c>"#,
        r#"<c r="A1" t="s"><v>-1</v></c>"#,
        r#"<c r="A1" t="b"><v>2</v></c>"#,
        r#"<c r="A1"><v>NaN</v></c>"#,
        r#"<c r="A1"><v>1e999</v></c>"#,
    ] {
        let parts = fixture(&format!(
            r#"<sheetData><row r="1">{cell}</row></sheetData>"#
        ));
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&parts)?)),
                Err(Error::Invalid(_))
            ),
            "{cell}"
        );
    }
    for attribute in ["fontId", "fillId", "borderId", "xfId"] {
        let mut parts = fixture("<sheetData/>");
        replace(
            &mut parts,
            "xl/styles.xml",
            &format!(r#"<styleSheet><cellXfs><xf {attribute}="999"/></cellXfs></styleSheet>"#),
        );
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&parts)?)),
                Err(Error::Invalid(_))
            ),
            "{attribute}"
        );
    }
    Ok(())
}

#[test]
fn rejects_malformed_reversed_and_overlapping_merges() -> TestResult {
    for ranges in [
        r#"<mergeCell ref="A1"/>"#,
        r#"<mergeCell ref="B2:A1"/>"#,
        r#"<mergeCell ref="A1:B2:C3"/>"#,
        r#"<mergeCell ref="A1:B2"/><mergeCell ref="B2:D3"/>"#,
        r#"<mergeCell ref="$A$1:$B$2"/>"#,
    ] {
        let parts = fixture(&format!("<mergeCells>{ranges}</mergeCells>"));
        assert!(
            matches!(
                parse_reader(Cursor::new(archive(&parts)?)),
                Err(Error::Invalid(_))
            ),
            "{ranges}"
        );
    }
    Ok(())
}
