use super::colors::Theme;
use super::geometry::points;
use crate::model::{Document, Element, ElementType};
use crate::xml::Node;

pub(super) struct Styles<'a> {
    pub doc: &'a mut Document,
    pub theme: &'a Theme,
}

impl Styles<'_> {
    pub fn drawing(&mut self, chain: &[&Node], element: &mut Element) -> bool {
        let mut preset = "rect";
        if chain.last().is_some_and(|n| n.name == "cxnSp") {
            preset = "line";
        }
        for shape in chain {
            if let Some(style) = shape.child("style") {
                if let Some(fill) = style.child("fillRef") {
                    element.fill = self.theme.fill_reference(fill, element.fill, self.doc);
                }
                if let Some(line) = style.child("lnRef") {
                    self.line_reference(element, line);
                }
                if style
                    .child("effectRef")
                    .is_some_and(|n| !matches!(n.attr("idx"), "" | "0"))
                {
                    self.doc.warn("PPTX theme effects are not supported.");
                }
            }
            if let Some(properties) = shape.child("spPr") {
                element.fill = self.theme.fill(properties, element.fill, self.doc);
                if let Some(line) = properties.child("ln") {
                    self.line(element, line);
                }
                if let Some(geometry) = properties.child("prstGeom") {
                    preset = geometry.attr("prst");
                    if preset == "roundRect" && has_zero_corner_radius(geometry) {
                        preset = "rect";
                    }
                }
                if properties.child("custGeom").is_some() {
                    preset = "custom";
                }
            }
        }
        element.kind = match preset {
            "rect" => ElementType::RECT,
            "ellipse" => ElementType::ELLIPSE,
            "line" => ElementType::LINE,
            _ => {
                self.doc
                    .warn("PPTX complex geometry is omitted; its text is retained.");
                return false;
            }
        };
        true
    }

    fn line_reference(&mut self, element: &mut Element, reference: &Node) {
        let index = reference.attr("idx").parse::<usize>().unwrap_or(0);
        if index == 0 {
            element.stroke = 0;
            return;
        }
        element.stroke = self.theme.color(reference, element.stroke, self.doc);
        if let Some(style) = self
            .theme
            .format_list("lnStyleLst")
            .and_then(|n| n.children.get(index - 1))
        {
            self.line(element, style);
        } else {
            self.doc
                .warn("Some PPTX theme line styles could not be resolved.");
        }
    }

    fn line(&mut self, element: &mut Element, node: &Node) {
        element.stroke = self.theme.fill(node, element.stroke, self.doc);
        if let Some(width) = points(node.attr("w")) {
            element.stroke_width = width.clamp(0.0, 1000.0);
        }
        if node
            .child("prstDash")
            .is_some_and(|n| n.attr("val") != "solid")
            || node.child("custDash").is_some()
            || ["headEnd", "tailEnd"].iter().any(|name| {
                node.child(name)
                    .is_some_and(|n| !matches!(n.attr("type"), "" | "none"))
            })
        {
            self.doc
                .warn("PPTX line dashes and arrowheads are not supported.");
        }
    }
}

fn has_zero_corner_radius(geometry: &Node) -> bool {
    let Some(adjustment) = geometry
        .child("avLst")
        .and_then(|list| list.named("gd").find(|guide| guide.attr("name") == "adj"))
    else {
        return false;
    };
    let mut formula = adjustment.attr("fmla").split_whitespace();
    formula.next() == Some("val")
        && formula.next().and_then(|value| value.parse::<i32>().ok()) == Some(0)
        && formula.next().is_none()
}
