use std::io::{Cursor, Write};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

pub type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;
pub type Parts = Vec<(&'static str, Vec<u8>)>;

pub const PNG: &[u8] = &[
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
    0xde, 0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0xd0, 0x9a, 0xdb, 0x0f,
    0x00, 0x02, 0x4b, 0x01, 0x57, 0x49, 0x20, 0x9e, 0x33, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e,
    0x44, 0xae, 0x42, 0x60, 0x82,
];

pub fn archive<S: AsRef<str>>(parts: &[(S, Vec<u8>)]) -> TestResult<Vec<u8>> {
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .last_modified_time(zip::DateTime::default());
    for (name, bytes) in parts {
        writer.start_file(name.as_ref(), options)?;
        writer.write_all(bytes)?;
    }
    Ok(writer.finish()?.into_inner())
}

pub fn xml(name: &'static str, text: &str) -> (&'static str, Vec<u8>) {
    (name, text.as_bytes().to_vec())
}

pub fn relationships(entries: &[(&str, &str, &str)]) -> String {
    let mut text = String::from(
        r#"<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"#,
    );
    for (id, kind, target) in entries {
        text.push_str(&format!(
            r#"<Relationship Id="{id}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/{kind}" Target="{target}"/>"#,
        ));
    }
    text.push_str("</Relationships>");
    text
}

pub fn content_types(overrides: &[(&str, &str)]) -> String {
    let mut text = String::from(
        r#"<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>"#,
    );
    for (part, kind) in overrides {
        text.push_str(&format!(
            r#"<Override PartName="/{part}" ContentType="application/vnd.openxmlformats-officedocument.{kind}+xml"/>"#,
        ));
    }
    text.push_str("</Types>");
    text
}

#[cfg(test)]
pub fn replace(parts: &mut Parts, name: &str, text: &str) {
    let part = parts.iter_mut().find(|(path, _)| *path == name);
    match part {
        Some((_, bytes)) => *bytes = text.as_bytes().to_vec(),
        None => panic!("Fixture part missing: {name}"),
    }
}
