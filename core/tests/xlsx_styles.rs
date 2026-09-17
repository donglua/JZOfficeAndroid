pub mod support;

use jz_office_core::parse_reader;
use std::io::Cursor;
use support::{
    package::{archive, replace, TestResult},
    xlsx,
};

#[test]
fn zero_alpha_spreadsheet_colors_are_opaque_for_font_fill_and_borders() -> TestResult {
    let mut parts = xlsx::parts();
    let styles = xlsx::STYLES
        .replace("FFFFFFFF", "00FFFFFF")
        .replace("FF24745C", "0024745C")
        .replace("FF112233", "00112233");
    replace(&mut parts, "xl/styles.xml", &styles);

    let document = parse_reader(Cursor::new(archive(&parts)?))?;

    let style = &document.cell_styles[1];
    assert_eq!(style.color, 0xffffffff);
    assert_eq!(style.fill, 0xff24745c);
    assert_eq!(style.borders, [Some(0xff112233); 4]);
    assert_eq!(document.cell_styles[0].fill, 0);
    Ok(())
}
