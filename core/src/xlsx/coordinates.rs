use super::{unsigned, MAX_COLUMNS, MAX_ROWS};
use crate::model::CellRange;
use crate::{Error, Result};

pub(super) fn coordinate(value: &str) -> Result<(u32, u32)> {
    let split = value.bytes().take_while(u8::is_ascii_uppercase).count();
    let (letters, digits) = value.split_at(split);
    if letters.is_empty() || digits.starts_with('0') {
        return Err(Error::Invalid("Invalid XLSX cell reference"));
    }
    let mut column = 0_u32;
    for byte in letters.bytes() {
        column = column
            .checked_mul(26)
            .and_then(|c| c.checked_add(u32::from(byte - b'A') + 1))
            .ok_or(Error::Limit("XLSX column exceeds 256"))?;
    }
    let row = unsigned(digits)?;
    Ok((row_index(row)?, column_index(column)?))
}

pub(super) fn row_index(value: u32) -> Result<u32> {
    if value == 0 {
        return Err(Error::Invalid("XLSX row is zero"));
    }
    if value > MAX_ROWS {
        return Err(Error::Limit("XLSX row exceeds 10000"));
    }
    Ok(value - 1)
}

pub(super) fn column_index(value: u32) -> Result<u32> {
    if value == 0 {
        return Err(Error::Invalid("XLSX column is zero"));
    }
    if value > MAX_COLUMNS {
        return Err(Error::Limit("XLSX column exceeds 256"));
    }
    Ok(value - 1)
}

pub(super) fn range(value: &str) -> Result<CellRange> {
    let (start, end) = value
        .split_once(':')
        .ok_or(Error::Invalid("Invalid XLSX merge range"))?;
    let (start_row, start_column) = coordinate(start)?;
    let (end_row, end_column) = coordinate(end)?;
    if start_row > end_row || start_column > end_column {
        return Err(Error::Invalid("Reversed XLSX merge range"));
    }
    Ok(CellRange {
        start_row,
        start_column,
        end_row,
        end_column,
    })
}

pub(super) fn overlaps(a: &CellRange, b: &CellRange) -> bool {
    a.start_row <= b.end_row
        && a.end_row >= b.start_row
        && a.start_column <= b.end_column
        && a.end_column >= b.start_column
}
