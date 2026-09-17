pub mod support;

use jz_office_core::{model::ElementType, parse_reader, Error, Package};
use std::io::Cursor;
use support::{
    docx,
    package::{archive, relationships, replace, TestResult},
    pptx,
};

#[test]
fn duplicate_zip_entries_are_rejected_before_parsing() -> TestResult {
    for (original, alias) in [
        ("word/document.xml", "word/documen2.xml"),
        ("_rels/.rels", "_rels/xrels"),
    ] {
        let mut parts = docx::parts();
        let payload = parts
            .iter()
            .find(|(name, _)| *name == original)
            .ok_or("Fixture part missing")?
            .1
            .clone();
        parts.push((alias, payload));
        let mut bytes = archive(&parts)?;
        let offsets: Vec<usize> = bytes
            .windows(alias.len())
            .enumerate()
            .filter_map(|(offset, value)| (value == alias.as_bytes()).then_some(offset))
            .collect();
        assert_eq!(offsets.len(), 2);
        for offset in offsets {
            bytes[offset..offset + alias.len()].copy_from_slice(original.as_bytes());
        }

        assert!(matches!(
            Package::new(Cursor::new(bytes)),
            Err(Error::Invalid("Duplicate package entry"))
        ));
    }
    Ok(())
}

#[test]
fn malformed_and_truncated_zip_inputs_are_rejected() -> TestResult {
    let valid = archive(&docx::parts())?;
    let cases = [
        Vec::new(),
        b"plain text, not an Office package".to_vec(),
        valid[..32].to_vec(),
    ];
    for bytes in cases {
        let result = parse_reader(Cursor::new(bytes));

        assert!(matches!(result, Err(Error::Zip(_))), "{result:?}");
    }
    Ok(())
}

#[test]
fn root_relationship_cannot_traverse_outside_package() -> TestResult {
    for target in [
        "../word/document.xml",
        "%2e%2e/word/document.xml",
        "file:///word/document.xml",
    ] {
        let mut parts = docx::parts();
        replace(
            &mut parts,
            "_rels/.rels",
            &relationships(&[("rOffice", "officeDocument", target)]),
        );

        let result = parse_reader(Cursor::new(archive(&parts)?));

        assert!(
            matches!(result, Err(Error::Invalid(_))),
            "{target}: {result:?}"
        );
    }
    Ok(())
}

#[test]
fn image_relationship_cannot_escape_package_even_with_matching_zip_entry() -> TestResult {
    for target in [
        "../../outside.png",
        "%2e%2e/%2e%2e/outside.png",
        "..%5c..%5coutside.png",
    ] {
        let mut parts = docx::parts();
        parts.push(("../outside.png", support::package::PNG.to_vec()));
        replace(
            &mut parts,
            "word/_rels/document.xml.rels",
            &relationships(&[("rImage", "image", target)]),
        );

        let result = parse_reader(Cursor::new(archive(&parts)?));

        assert!(
            matches!(result, Err(Error::Invalid(_))),
            "{target}: {result:?}"
        );
    }
    Ok(())
}

#[test]
fn external_docx_images_are_excluded_even_when_target_exists_in_archive() -> TestResult {
    for target in ["media/pixel.png", "https://example.invalid/pixel.png"] {
        let mut parts = docx::parts();
        let rels = relationships(&[("rImage", "image", target)])
            .replace("Id=\"rImage\"", "TargetMode=\"eXtErNaL\" Id=\"rImage\"");
        replace(&mut parts, "word/_rels/document.xml.rels", &rels);

        let document = parse_reader(Cursor::new(archive(&parts)?))?;

        assert!(document
            .blocks
            .iter()
            .all(|element| !matches!(element.kind, ElementType::IMAGE)));
        assert!(document
            .blocks
            .iter()
            .any(|element| matches!(element.kind, ElementType::TEXT)));
        assert!(!document.warnings.is_empty());
    }
    Ok(())
}

#[test]
fn external_pptx_image_is_excluded_without_losing_slides() -> TestResult {
    let mut parts = pptx::parts();
    let rels = relationships(&[
        ("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"),
        ("rImage", "image", "../media/pixel.png"),
    ])
    .replace("Id=\"rImage\"", "TargetMode=\"External\" Id=\"rImage\"");
    replace(&mut parts, "ppt/slides/_rels/slide2.xml.rels", &rels);

    let document = parse_reader(Cursor::new(archive(&parts)?))?;

    assert_eq!(document.pages.len(), 2);
    assert!(document.pages[0]
        .elements
        .iter()
        .all(|element| !matches!(element.kind, ElementType::IMAGE)));
    Ok(())
}

#[test]
fn external_office_document_relationship_is_not_a_local_entry_point() -> TestResult {
    let mut parts = docx::parts();
    let rels = relationships(&[("rOffice", "officeDocument", "word/document.xml")])
        .replace("Id=\"rOffice\"", "TargetMode=\"External\" Id=\"rOffice\"");
    replace(&mut parts, "_rels/.rels", &rels);

    let result = parse_reader(Cursor::new(archive(&parts)?));

    assert!(matches!(result, Err(Error::Invalid(_))));
    Ok(())
}

#[test]
fn relationship_api_excludes_external_and_normalizes_internal_paths() -> TestResult {
    let mut parts = docx::parts();
    let rels = relationships(&[
        ("rImage", "image", "media/../media/%70ixel.png"),
        ("rRemote", "image", "https://example.invalid/image.png"),
    ])
    .replace("Id=\"rRemote\"", "TargetMode=\"External\" Id=\"rRemote\"");
    replace(&mut parts, "word/_rels/document.xml.rels", &rels);
    let mut package = Package::new(Cursor::new(archive(&parts)?))?;

    let targets = package.relationships("word/document.xml")?;

    assert_eq!(targets.len(), 1);
    assert_eq!(
        targets.get("rImage").map(String::as_str),
        Some("word/media/pixel.png")
    );
    assert!(!targets.contains_key("rRemote"));
    Ok(())
}

#[test]
fn dtd_is_rejected_in_utf8_and_utf16_document_parts() -> TestResult {
    let xml = docx::DOCUMENT.replacen(
        "?>",
        "?><!DOCTYPE w:document [<!ENTITY fixture 'blocked'>]>",
        1,
    );
    let utf16: Vec<u8> = [0xff, 0xfe]
        .into_iter()
        .chain(
            xml.replace("UTF-8", "UTF-16")
                .encode_utf16()
                .flat_map(u16::to_le_bytes),
        )
        .collect();
    for bytes in [xml.into_bytes(), utf16] {
        let mut parts = docx::parts();
        parts.retain(|(name, _)| *name != "word/document.xml");
        parts.push(("word/document.xml", bytes));

        let result = parse_reader(Cursor::new(archive(&parts)?));

        assert!(
            matches!(result, Err(Error::Xml(roxmltree::Error::DtdDetected))),
            "{result:?}"
        );
    }
    Ok(())
}

#[test]
fn dtd_is_rejected_before_resolving_package_relationships() -> TestResult {
    let mut parts = docx::parts();
    let xml = relationships(&[("rOffice", "officeDocument", "word/document.xml")]).replacen(
        "?>",
        "?><!DOCTYPE Relationships>",
        1,
    );
    replace(&mut parts, "_rels/.rels", &xml);

    let result = parse_reader(Cursor::new(archive(&parts)?));

    assert!(matches!(
        result,
        Err(Error::Xml(roxmltree::Error::DtdDetected))
    ));
    Ok(())
}
