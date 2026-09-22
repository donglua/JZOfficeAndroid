pub mod support;

use jz_office_core::{
    model::{Document, ElementType},
    parse_reader,
};
use std::io::Cursor;
use support::{checks, package, package::TestResult, pptx};

const FALLBACK: &str = "PPTX numbered lists use decimal numbers.";

#[test]
fn formats_common_schemes_when_numbering_starts_at_four() -> TestResult {
    for (scheme, first, second) in [
        ("arabicPeriod", "4. ", "5. "),
        ("arabicParenR", "4) ", "5) "),
        ("arabicParenBoth", "(4) ", "(5) "),
        ("arabicPlain", "4 ", "5 "),
        ("alphaLcPeriod", "d. ", "e. "),
        ("alphaUcPeriod", "D. ", "E. "),
        ("alphaLcParenR", "d) ", "e) "),
        ("alphaUcParenR", "D) ", "E) "),
        ("alphaLcParenBoth", "(d) ", "(e) "),
        ("alphaUcParenBoth", "(D) ", "(E) "),
        ("romanLcPeriod", "iv. ", "v. "),
        ("romanUcPeriod", "IV. ", "V. "),
        ("romanLcParenR", "iv) ", "v) "),
        ("romanUcParenR", "IV) ", "V) "),
        ("romanLcParenBoth", "(iv) ", "(v) "),
        ("romanUcParenBoth", "(IV) ", "(V) "),
    ] {
        // Given a new sequence and a following paragraph using the same scheme.
        let body = format!("{}{}", numbered(scheme, "4", 0), numbered(scheme, "", 0));
        // When the PPTX package is parsed.
        let doc = parse_body(&body)?;
        // Then punctuation, case, and the starting number are preserved.
        assert_eq!(bullets(&doc)?, [first, second], "{scheme}");
        assert!(!doc.warnings.iter().any(|w| w == FALLBACK), "{scheme}");
    }
    Ok(())
}

#[test]
fn repeats_letters_when_alphabet_numbering_rolls_over() -> TestResult {
    for (start, expected) in [
        ("26", ["z. ", "aa. "]),
        ("51", ["yy. ", "zz. "]),
        ("52", ["zz. ", "aaa. "]),
    ] {
        // Given numbering at an alphabetical boundary defined by DrawingML.
        let body = format!(
            "{}{}",
            numbered("alphaLcPeriod", start, 0),
            numbered("alphaLcPeriod", "", 0)
        );
        // When the PPTX package is parsed.
        let doc = parse_body(&body)?;
        // Then letters repeat after z rather than behaving like column names.
        assert_eq!(bullets(&doc)?, expected);
        assert!(!doc.warnings.iter().any(|w| w == FALLBACK));
    }
    Ok(())
}

#[test]
fn tracks_each_level_and_restarts_children_when_the_parent_advances() -> TestResult {
    // Given two parent items, nested sequences, and repeated startAt attributes.
    let body = [
        numbered("arabicParenR", "3", 0),
        numbered("alphaUcPeriod", "2", 1),
        numbered("alphaUcPeriod", "2", 1),
        numbered("arabicParenR", "3", 0),
        numbered("alphaUcPeriod", "2", 1),
    ]
    .concat();
    // When the PPTX package is parsed.
    let doc = parse_body(&body)?;
    // Then each level counts independently and each new child sequence restarts.
    assert_eq!(bullets(&doc)?, ["3) ", "B. ", "C. ", "4) ", "B. "]);
    Ok(())
}

#[test]
fn inherits_numbering_when_direct_paragraph_properties_omit_it() -> TestResult {
    // Given a list-style default and a direct request to suppress one bullet.
    let body = r#"<a:lstStyle><a:lvl1pPr><a:buAutoNum type="romanUcParenR" startAt="9"/>
</a:lvl1pPr></a:lstStyle><a:p/><a:p><a:pPr><a:buNone/></a:pPr></a:p><a:p/>"#;
    // When the PPTX package is parsed.
    let doc = parse_body(body)?;
    // Then the inherited sequence is preserved without numbering the suppressed item.
    assert_eq!(bullets(&doc)?, ["IX) ", "", "X) "]);
    assert!(!doc.warnings.iter().any(|w| w == FALLBACK));
    Ok(())
}

#[test]
fn falls_back_to_decimal_when_scheme_is_unsupported() -> TestResult {
    for scheme in ["circleNumDbPlain", "thaiNumPeriod", "unknown", ""] {
        // Given a scheme outside the supported set.
        let body = numbered(scheme, "7", 0);
        // When the PPTX package is parsed.
        let doc = parse_body(&body)?;
        // Then fallback is visible in both the rendered label and warnings.
        assert_eq!(bullets(&doc)?, ["7. "]);
        assert!(doc.warnings.iter().any(|w| w == FALLBACK));
    }
    Ok(())
}

#[test]
fn falls_back_to_decimal_when_roman_sequence_exceeds_3999() -> TestResult {
    // Given a Roman sequence crossing the supported conventional numeral range.
    let body = format!(
        "{}{}",
        numbered("romanUcPeriod", "3999", 0),
        numbered("romanUcPeriod", "", 0)
    );
    // When the PPTX package is parsed.
    let doc = parse_body(&body)?;
    // Then subtractive notation is exact and the out-of-range value stays bounded.
    assert_eq!(bullets(&doc)?, ["MMMCMXCIX. ", "4000. "]);
    assert!(doc.warnings.iter().any(|w| w == FALLBACK));
    Ok(())
}

#[test]
fn bounds_start_at_when_attribute_is_missing_invalid_or_out_of_range() -> TestResult {
    for (start, expected) in [
        ("", "1) "),
        ("NaN", "1) "),
        ("-1", "1) "),
        ("0", "1) "),
        ("32767", "32767) "),
        ("32768", "32767) "),
        ("4294967295", "32767) "),
    ] {
        // Given an absent, malformed, or boundary starting number.
        let body = numbered("arabicParenR", start, 0);
        // When the PPTX package is parsed.
        let doc = parse_body(&body)?;
        // Then existing bounded startAt parsing applies to the new formats.
        assert_eq!(bullets(&doc)?, [expected], "{start}");
    }
    Ok(())
}

fn numbered(scheme: &str, start: &str, level: u8) -> String {
    let start_attribute = if start.is_empty() {
        String::new()
    } else {
        format!(r#" startAt="{start}""#)
    };
    format!(
        r#"<a:p><a:pPr lvl="{level}"><a:buAutoNum type="{scheme}"{start_attribute}/>
</a:pPr><a:r><a:t>Item</a:t></a:r></a:p>"#
    )
}

fn parse_body(body: &str) -> TestResult<Document> {
    let mut parts = pptx::parts();
    package::replace(
        &mut parts,
        "ppt/slides/slide2.xml",
        &format!(
            r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="Numbered list"/>
<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/>
<a:ext cx="4572000" cy="2743200"/></a:xfrm></p:spPr>
<p:txBody>{body}</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"#
        ),
    );
    Ok(parse_reader(Cursor::new(package::archive(&parts)?))?)
}

fn bullets(document: &Document) -> TestResult<Vec<&str>> {
    Ok(
        checks::element(&document.pages[0].elements, ElementType::TEXT)?
            .paragraphs
            .iter()
            .map(|p| p.bullet.as_str())
            .collect(),
    )
}
