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

#[test]
fn tint_changes_hls_lightness_for_rgb_colors() -> TestResult {
    for (rgb, tint, expected) in [
        ("FF0000", "0.5", 0xffff8080_u32),
        ("FF0000", "-0.5", 0xff800000),
        ("0000FF", "0.5", 0xff8080ff),
        ("C8C8C8", "-0.5", 0xff646464),
        ("000000", "0.5", 0xff808080),
        ("FFFFFF", "-0.5", 0xff808080),
        ("123456", "0", 0xff123456),
        ("123456", "1", 0xffffffff),
        ("123456", "-1", 0xff000000),
        ("000000", "-0.5", 0xff000000),
        ("FFFFFF", "0.5", 0xffffffff),
    ] {
        let styles = xlsx::STYLES.replace(
            "rgb=\"FFFFFFFF\"",
            &format!("rgb=\"{rgb}\" tint=\"{tint}\""),
        );

        let document = parse(&styles)?;

        assert_eq!(document.cell_styles[1].color, expected, "{rgb} tint={tint}");
        assert!(!document.warnings.iter().any(|w| w.contains("tint")));
    }
    Ok(())
}

#[test]
fn tint_applies_after_theme_and_indexed_color_resolution() -> TestResult {
    let colors = "<colors><indexedColors><rgbColor rgb=\"000000FF\"/></indexedColors></colors>";
    let styles = indexed_styles(0, 0, 0, colors)
        .replace("indexed=\"0\"", "indexed=\"0\" tint=\"0.5\"")
        .replace("rgb=\"FF202124\"", "theme=\"0\" tint=\"-0.5\"");

    let document = parse(&styles)?;

    let style = &document.cell_styles[1];
    assert_eq!(style.color, 0xff8080ff);
    assert_eq!(style.fill, 0xff8080ff);
    assert_eq!(style.borders, [Some(0xff8080ff); 4]);
    assert_eq!(document.cell_styles[0].color, 0xff808080);
    assert!(document
        .warnings
        .iter()
        .any(|w| w.contains("theme is missing")));
    assert!(!document.warnings.iter().any(|w| w.contains("tint")));
    Ok(())
}

#[test]
fn positive_tint_preserves_hue_and_saturation_instead_of_blending_rgb() -> TestResult {
    let styles = xlsx::STYLES.replace("rgb=\"FFFFFFFF\"", "rgb=\"336699\" tint=\"0.5\"");

    let document = parse(&styles)?;

    let actual = document.cell_styles[1].color;
    // HLS lightness 0.4 -> 0.7 yields RGB (140.25, 178.5, 216.75).
    for (shift, expected) in [(16, 140_u32), (8, 179), (0, 217)] {
        assert!(((actual >> shift) & 255).abs_diff(expected) <= 1);
    }
    assert_eq!(actual >> 24, 255);
    Ok(())
}

#[test]
fn invalid_tints_remain_rejected() -> TestResult {
    for tint in ["NaN", "inf", "-inf", "1.01", "-1.01", "oops"] {
        let mut parts = xlsx::parts();
        replace(
            &mut parts,
            "xl/styles.xml",
            &xlsx::STYLES.replace(
                "rgb=\"FFFFFFFF\"",
                &format!("rgb=\"FFFFFFFF\" tint=\"{tint}\""),
            ),
        );

        let result = parse_reader(Cursor::new(archive(&parts)?));

        assert!(matches!(
            result,
            Err(Error::Invalid("Invalid XLSX color tint"))
        ));
    }
    Ok(())
}
