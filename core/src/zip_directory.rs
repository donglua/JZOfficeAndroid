use crate::{Error, Result};
use std::io::{Read, Seek, SeekFrom};

// zip 2.4 deduplicates names before exposing entries; validate physical records first.
pub(crate) fn entry_count(reader: &mut (impl Read + Seek), length: u64) -> Result<usize> {
    let tail_start = length.saturating_sub(65557);
    reader.seek(SeekFrom::Start(tail_start))?;
    let mut tail = Vec::new();
    reader.read_to_end(&mut tail)?;
    let offset = tail
        .windows(22)
        .enumerate()
        .rfind(|(offset, header)| {
            header[..4] == *b"PK\x05\x06"
                && offset + 22 + usize::from(word(header, 20)) == tail.len()
        })
        .map(|(offset, _)| offset)
        .ok_or(zip::result::ZipError::InvalidArchive(
            "Missing ZIP end record",
        ))?;
    let footer = &tail[offset..];
    let count = word(footer, 10);
    let size = dword(footer, 12);
    let start = dword(footer, 16);
    if count == u16::MAX || size == u32::MAX || start == u32::MAX {
        return Err(Error::Invalid("ZIP64 packages are not supported"));
    }
    if word(footer, 4) != 0 || word(footer, 6) != 0 || word(footer, 8) != count {
        return Err(Error::Invalid("Split ZIP packages are not supported"));
    }
    if count > 4096 {
        return Err(Error::Limit("Package exceeds 4096 entries"));
    }
    let end = u64::from(start) + u64::from(size);
    if end != tail_start + offset as u64 {
        return Err(Error::Invalid("Invalid ZIP directory bounds"));
    }
    let mut position = u64::from(start);
    reader.seek(SeekFrom::Start(position))?;
    for _ in 0..count {
        if position + 46 > end {
            return Err(Error::Invalid("Truncated ZIP directory"));
        }
        let mut header = [0_u8; 46];
        reader.read_exact(&mut header)?;
        if header[..4] != *b"PK\x01\x02" {
            return Err(Error::Invalid("Invalid ZIP directory entry"));
        }
        position += 46
            + u64::from(word(&header, 28))
            + u64::from(word(&header, 30))
            + u64::from(word(&header, 32));
        if position > end {
            return Err(Error::Invalid("ZIP entry exceeds directory bounds"));
        }
        reader.seek(SeekFrom::Start(position))?;
    }
    if position != end {
        return Err(Error::Invalid("ZIP entry count does not match directory"));
    }
    Ok(usize::from(count))
}

fn word(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn dword(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}
