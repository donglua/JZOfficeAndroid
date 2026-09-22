pub mod support;

use jz_office_core::{model::Document, parse_reader, Error};
use std::io::Cursor;
use support::{
    package::{archive, replace, TestResult},
    xlsx,
};

fn parse(styles: &str) -> TestResult<Document> {
    let mut parts = xlsx::parts();
    replace(&mut parts, "xl/styles.xml", styles);
    Ok(parse_reader(Cursor::new(archive(&parts)?))?)
}

fn indexed_styles(font: u32, fill: u32, border: u32, colors: &str) -> String {
    xlsx::STYLES
        .replace("rgb=\"FFFFFFFF\"", &format!("indexed=\"{font}\""))
        .replace("rgb=\"FF24745C\"", &format!("indexed=\"{fill}\""))
        .replace("rgb=\"FF112233\"", &format!("indexed=\"{border}\""))
        .replace("</styleSheet>", &format!("{colors}</styleSheet>"))
}

#[test]
fn custom_palette_reaches_serialized_font_fill_and_borders() -> TestResult {
    let mut palette = String::from("<colors><indexedColors>");
    for index in 0..64 {
        let color = match index {
            0 => "00123456",
            8 => "00ABCDEF",
            63 => "00765432",
            _ => "00000000",
        };
        palette.push_str(&format!("<rgbColor rgb=\"{color}\"/>"));
    }
    palette.push_str("</indexedColors></colors>");

    let document = parse(&indexed_styles(0, 8, 63, &palette))?;

    let json = serde_json::to_value(&document)?;
    assert_eq!(json["cellStyles"][1]["color"], 0xff123456_u32);
    assert_eq!(json["cellStyles"][1]["fill"], 0xffabcdef_u32);
    assert_eq!(
        json["cellStyles"][1]["borders"],
        serde_json::to_value([0xff765432_u32; 4])?
    );
    assert!(!document.warnings.iter().any(|w| w.contains("palette")));
    Ok(())
}

#[test]
fn default_palette_survives_missing_empty_or_recent_colors() -> TestResult {
    for colors in [
        "",
        "<colors><indexedColors/></colors>",
        "<colors><mruColors><color rgb=\"FF123456\"/></mruColors></colors>",
    ] {
        let document = parse(&indexed_styles(2, 3, 4, colors))?;

        let style = &document.cell_styles[1];
        assert_eq!(style.color, 0xffff0000);
        assert_eq!(style.fill, 0xff00ff00);
        assert_eq!(style.borders, [Some(0xff0000ff); 4]);
        assert!(!document.warnings.iter().any(|w| w.contains("palette")));
    }
    Ok(())
}

#[test]
fn partial_palette_preserves_defaults_for_unlisted_indexes() -> TestResult {
    let colors = "<colors><indexedColors><rgbColor rgb=\"00123456\"/></indexedColors></colors>";

    let document = parse(&indexed_styles(0, 3, 4, colors))?;

    let style = &document.cell_styles[1];
    assert_eq!(style.color, 0xff123456);
    assert_eq!(style.fill, 0xff00ff00);
    assert_eq!(style.borders, [Some(0xff0000ff); 4]);
    Ok(())
}

#[test]
fn system_color_indexes_keep_their_context_fallbacks() -> TestResult {
    let colors = format!(
        "<colors><indexedColors>{}</indexedColors></colors>",
        "<rgbColor rgb=\"FFFF0000\"/>".repeat(66)
    );

    let document = parse(&indexed_styles(64, 65, 64, &colors))?;

    let style = &document.cell_styles[1];
    assert_eq!(style.color, 0xff202124);
    assert_eq!(style.fill, 0);
    assert_eq!(style.borders, [Some(0xff202124); 4]);
    Ok(())
}

#[test]
fn malformed_custom_palette_colors_are_rejected() -> TestResult {
    for color in ["", "XYZ123", "12345", "GG123456"] {
        let colors =
            format!("<colors><indexedColors><rgbColor rgb=\"{color}\"/></indexedColors></colors>");
        let mut parts = xlsx::parts();
        replace(
            &mut parts,
            "xl/styles.xml",
            &indexed_styles(0, 0, 0, &colors),
        );

        let result = parse_reader(Cursor::new(archive(&parts)?));

        assert!(matches!(
            result,
            Err(Error::Invalid("Invalid XLSX RGB color"))
        ));
    }
    Ok(())
}
