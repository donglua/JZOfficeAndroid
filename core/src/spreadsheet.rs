use crate::model::VerticalAlignment;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Sheet {
    pub name: String,
    pub row_heights: Vec<f32>,
    pub column_widths: Vec<f32>,
    pub cells: Vec<Cell>,
    pub merges: Vec<CellRange>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Cell {
    pub row: u32,
    pub column: u32,
    pub text: String,
    pub style: u32,
    pub numeric: bool,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CellRange {
    pub start_row: u32,
    pub start_column: u32,
    pub end_row: u32,
    pub end_column: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CellStyle {
    pub font_size: f32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub color: u32,
    pub fill: u32,
    pub alignment: u8,
    pub vertical_alignment: VerticalAlignment,
    pub wrap: bool,
    pub borders: [Option<u32>; 4],
}

impl Default for CellStyle {
    fn default() -> Self {
        Self {
            font_size: 11.0,
            bold: false,
            italic: false,
            underline: false,
            color: 0xff202124,
            fill: 0,
            alignment: 3,
            vertical_alignment: VerticalAlignment::Bottom,
            wrap: false,
            borders: [None; 4],
        }
    }
}
