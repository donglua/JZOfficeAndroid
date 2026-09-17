pub mod support;

use jz_office_core::{parse_reader, Error, Package};
use std::io::Cursor;
use support::{
    docx,
    package::{archive, TestResult},
};

#[test]
fn archive_comment_is_accepted() -> TestResult {
    let mut bytes = archive(&docx::parts())?;
    let footer = bytes.len() - 22;
    let comment = b"JZ Office fixture";
    bytes[footer + 20..footer + 22].copy_from_slice(&(comment.len() as u16).to_le_bytes());
    bytes.extend_from_slice(comment);
    assert!(!parse_reader(Cursor::new(bytes))?.blocks.is_empty());
    Ok(())
}

#[test]
fn declared_entry_limit_is_checked_before_loading_metadata() -> TestResult {
    let mut bytes = archive(&docx::parts())?;
    let footer = bytes.len() - 22;
    bytes[footer + 8..footer + 10].copy_from_slice(&4097_u16.to_le_bytes());
    bytes[footer + 10..footer + 12].copy_from_slice(&4097_u16.to_le_bytes());
    assert!(matches!(
        Package::new(Cursor::new(bytes)),
        Err(Error::Limit(_))
    ));
    Ok(())
}

#[test]
fn inconsistent_directory_count_and_record_lengths_are_rejected() -> TestResult {
    let valid = archive(&docx::parts())?;
    let footer = valid.len() - 22;
    let directory = u32::from_le_bytes(valid[footer + 16..footer + 20].try_into()?) as usize;
    let mut wrong_count = valid.clone();
    wrong_count[footer + 8..footer + 10].copy_from_slice(&1_u16.to_le_bytes());
    wrong_count[footer + 10..footer + 12].copy_from_slice(&1_u16.to_le_bytes());
    let mut wrong_length = valid;
    wrong_length[directory + 28..directory + 30].copy_from_slice(&u16::MAX.to_le_bytes());
    for bytes in [wrong_count, wrong_length] {
        assert!(matches!(
            Package::new(Cursor::new(bytes)),
            Err(Error::Invalid(_))
        ));
    }
    Ok(())
}

#[test]
fn zip64_sentinel_is_explicitly_unsupported() -> TestResult {
    let mut bytes = archive(&docx::parts())?;
    let footer = bytes.len() - 22;
    bytes[footer + 10..footer + 12].copy_from_slice(&u16::MAX.to_le_bytes());
    assert!(matches!(
        Package::new(Cursor::new(bytes)),
        Err(Error::Invalid("ZIP64 packages are not supported"))
    ));
    Ok(())
}
