use jz_office_core::{Error, Package};
use std::io::{self, Cursor, Read};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;
const MIB: u64 = 1024 * 1024;

fn padded_archive(size: u64, method: CompressionMethod) -> TestResult<Vec<u8>> {
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    writer.start_file(
        "padding.bin",
        SimpleFileOptions::default().compression_method(method),
    )?;
    io::copy(&mut io::repeat(0).take(size), &mut writer)?;
    Ok(writer.finish()?.into_inner())
}

#[test]
fn input_limit_accepts_64_mib_and_rejects_one_byte_more() -> TestResult {
    let overhead = padded_archive(0, CompressionMethod::Stored)?.len() as u64;
    let mut bytes = padded_archive(64 * MIB - overhead, CompressionMethod::Stored)?;
    assert_eq!(bytes.len() as u64, 64 * MIB);
    Package::new(Cursor::new(bytes.clone()))?;
    bytes.push(0);
    assert!(matches!(
        Package::new(Cursor::new(bytes)),
        Err(Error::Limit("Input exceeds 64 MiB"))
    ));
    Ok(())
}

#[test]
fn expanded_limit_accepts_128_mib_and_rejects_one_byte_more() -> TestResult {
    for (size, accepted) in [(128 * MIB, true), (128 * MIB + 1, false)] {
        let bytes = padded_archive(size, CompressionMethod::Deflated)?;
        assert!(bytes.len() as u64 <= 64 * MIB);
        let result = Package::new(Cursor::new(bytes));
        if accepted {
            result?;
        } else {
            assert!(matches!(
                result,
                Err(Error::Limit("Expanded package exceeds 128 MiB"))
            ));
        }
    }
    Ok(())
}
