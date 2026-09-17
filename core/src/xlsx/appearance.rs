use super::{boolean, colors::Colors, dimension};
use crate::model::{CellStyle, Document, VerticalAlignment};
use crate::xml::Node;
use crate::Result;

pub(super) fn font(node: &Node, colors: &Colors, document: &mut Document) -> Result<CellStyle> {
    let mut style = CellStyle::default();
    if let Some(size) = node.child("sz") {
        style.font_size = dimension(size.attr("val"), 11.0, 409.0)?.max(1.0);
    }
    style.bold = node
        .child("b")
        .map_or(Ok(false), |n| boolean(n.attr("val"), true))?;
    style.italic = node
        .child("i")
        .map_or(Ok(false), |n| boolean(n.attr("val"), true))?;
    style.underline = node.child("u").is_some_and(|n| n.attr("val") != "none");
    style.color = colors.color(node.child("color"), style.color, document)?;
    if node.child("strike").is_some() || node.child("vertAlign").is_some() {
        document.warn("Strikethrough and superscript/subscript fonts are not supported");
    }
    Ok(style)
}

pub(super) fn fill(node: &Node, colors: &Colors, document: &mut Document) -> Result<u32> {
    if let Some(pattern) = node.child("patternFill") {
        return match pattern.attr("patternType") {
            "solid" => colors.color(pattern.child("fgColor"), 0, document),
            "" | "none" | "gray125" => Ok(0),
            _ => {
                document.warn("Pattern fills are not supported");
                Ok(0)
            }
        };
    }
    if node.child("gradientFill").is_some() {
        document.warn("Gradient fills are not supported");
    }
    Ok(0)
}

pub(super) fn border(
    node: &Node,
    colors: &Colors,
    document: &mut Document,
) -> Result<[Option<u32>; 4]> {
    let mut result = [None; 4];
    for (index, name) in ["left", "top", "right", "bottom"].iter().enumerate() {
        if let Some(edge) = node.child(name) {
            if !matches!(edge.attr("style"), "" | "none") {
                result[index] = Some(colors.color(edge.child("color"), 0xff202124, document)?);
                if edge.attr("style") != "thin" {
                    document
                        .warn("Border weights and dash patterns are displayed as thin solid lines");
                }
            }
        }
    }
    if node
        .child("diagonal")
        .is_some_and(|n| !matches!(n.attr("style"), "" | "none"))
    {
        document.warn("Diagonal borders are not supported");
    }
    Ok(result)
}

pub(super) fn alignment(style: &mut CellStyle, node: &Node, document: &mut Document) -> Result<()> {
    if !node.attr("horizontal").is_empty() {
        style.alignment = match node.attr("horizontal") {
            "left" => 0,
            "center" => 1,
            "right" => 2,
            "general" => 3,
            _ => {
                document.warn("Unsupported cell alignment uses general alignment");
                3
            }
        };
    }
    if !node.attr("vertical").is_empty() {
        style.vertical_alignment = match node.attr("vertical") {
            "top" => VerticalAlignment::Top,
            "center" => VerticalAlignment::Center,
            "bottom" => VerticalAlignment::Bottom,
            _ => {
                document.warn("Unsupported vertical alignment uses bottom alignment");
                VerticalAlignment::Bottom
            }
        };
    }
    style.wrap = boolean(node.attr("wrapText"), style.wrap)?;
    if ["textRotation", "indent", "readingOrder", "shrinkToFit"]
        .iter()
        .any(|name| !matches!(node.attr(name), "" | "0" | "false"))
    {
        document
            .warn("Text rotation, indentation, reading order and shrink-to-fit are not supported");
    }
    Ok(())
}
