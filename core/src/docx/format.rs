use super::MAX_COORDINATE;
use crate::model::{Paragraph, Run};
use crate::xml::{self, Node};

pub(super) fn enabled(value: &str) -> bool {
    !["0", "false", "off", "none"]
        .iter()
        .any(|off| value.eq_ignore_ascii_case(off))
}

pub(super) fn points(value: &str, fallback: f32) -> f32 {
    (xml::number(value, fallback * 20.0) / 20.0).clamp(0.0, MAX_COORDINATE)
}

pub(super) fn font(run: &mut Run, properties: Option<&Node>) {
    let Some(properties) = properties else {
        return;
    };
    for node in &properties.children {
        let value = node.attr("val");
        match node.name.as_str() {
            "b" => run.bold = enabled(value),
            "i" => run.italic = enabled(value),
            "u" => run.underline = enabled(value),
            "sz" => run.size = (xml::number(value, run.size * 2.0) / 2.0).clamp(1.0, 400.0),
            "color" => {
                run.color = if value.eq_ignore_ascii_case("auto") {
                    0xff202124
                } else {
                    xml::color(value, run.color)
                }
            }
            _ => (),
        }
    }
}

#[derive(Clone, Default)]
pub(super) struct ParagraphFormat {
    pub paragraph: Paragraph,
    pub number: String,
    pub level: u8,
    pub has_indent: bool,
}

impl ParagraphFormat {
    pub fn apply(&mut self, properties: Option<&Node>) {
        let Some(properties) = properties else {
            return;
        };
        for node in &properties.children {
            match node.name.as_str() {
                "jc" => {
                    self.paragraph.alignment = match node.attr("val") {
                        "center" => 1,
                        "right" | "end" => 2,
                        _ => 0,
                    }
                }
                "spacing" => {
                    self.paragraph.before = points(node.attr("before"), self.paragraph.before);
                    self.paragraph.after = points(node.attr("after"), self.paragraph.after);
                }
                "ind" => {
                    let left = if node.attr("start").is_empty() {
                        node.attr("left")
                    } else {
                        node.attr("start")
                    };
                    if !left.is_empty() {
                        self.paragraph.indent = points(left, self.paragraph.indent);
                        self.has_indent = true;
                    }
                }
                "numPr" => {
                    if let Some(number) = node.child("numId") {
                        self.number = number.attr("val").to_owned();
                    }
                    if let Some(level) = node.child("ilvl") {
                        self.level = level.attr("val").parse::<u8>().map_or(0, |n| n.min(8));
                    }
                }
                _ => (),
            }
        }
    }
}
