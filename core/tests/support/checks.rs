use super::package::TestResult;
use jz_office_core::model::{Element, ElementType, Paragraph};
use std::{io, mem};

pub fn element(elements: &[Element], kind: ElementType) -> TestResult<&Element> {
    elements
        .iter()
        .find(|element| mem::discriminant(&element.kind) == mem::discriminant(&kind))
        .ok_or_else(|| io::Error::other(format!("Missing {kind:?} fixture element")).into())
}

pub fn text(paragraphs: &[Paragraph]) -> String {
    paragraphs
        .iter()
        .flat_map(|paragraph| &paragraph.runs)
        .map(|run| run.text.as_str())
        .collect()
}

pub fn near(actual: f32, expected: f32) {
    assert!(
        (actual - expected).abs() < 0.001,
        "Expected {expected} points, got {actual}"
    );
}

pub fn bounds(element: &Element, expected: [f32; 4]) {
    for (actual, expected) in [element.x, element.y, element.width, element.height]
        .into_iter()
        .zip(expected)
    {
        near(actual, expected);
    }
}
