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
fn input_limit_accepts_128_mib_and_rejects_one_byte_more() -> TestResult {
    let overhead = padded_archive(0, CompressionMethod::Stored)?.len() as u64;
    let mut bytes = padded_archive(128 * MIB - overhead, CompressionMethod::Stored)?;
    assert_eq!(bytes.len() as u64, 128 * MIB);
    Package::new(Cursor::new(bytes.clone()))?;
    bytes.push(0);
    assert!(matches!(
        Package::new(Cursor::new(bytes)),
        Err(Error::Limit("Input exceeds 128 MiB"))
    ));
    Ok(())
}

#[test]
fn expanded_limit_accepts_256_mib_and_rejects_one_byte_more() -> TestResult {
    for (size, accepted) in [(256 * MIB, true), (256 * MIB + 1, false)] {
        let bytes = padded_archive(size, CompressionMethod::Deflated)?;
        assert!(bytes.len() as u64 <= 128 * MIB);
        let result = Package::new(Cursor::new(bytes));
        if accepted {
            result?;
        } else {
            assert!(matches!(
                result,
                Err(Error::Limit("Expanded package exceeds 256 MiB"))
            ));
        }
    }
    Ok(())
}

#[test]
fn read_budget_accepts_256_mib_then_rejects_more_without_relaxing_part_limit() -> TestResult {
    let bytes = padded_archive(16 * MIB, CompressionMethod::Deflated)?;
    let mut package = Package::new(Cursor::new(bytes))?;
    assert!(matches!(
        package.read("padding.bin", 16 * MIB - 1),
        Err(Error::Limit("Package part is too large"))
    ));
    for _ in 0..16 {
        assert_eq!(
            package.read("padding.bin", 16 * MIB)?.len() as u64,
            16 * MIB
        );
    }
    assert!(matches!(
        package.read("padding.bin", 16 * MIB),
        Err(Error::Limit("Package read budget exceeded"))
    ));
    Ok(())
}
