use super::flag;
use crate::model::{Document, Element, ElementType};
use crate::xml::{number, Node};

const MAX_POINT: f32 = 100_000.0;

pub(super) fn points(value: &str) -> Option<f32> {
    value
        .parse::<f32>()
        .ok()
        .map(|v| v / 12700.0)
        .filter(|v| v.is_finite() && v.abs() <= MAX_POINT)
}

#[derive(Clone, Copy)]
pub(super) struct Bounds {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    rotation: f32,
}

impl Bounds {
    pub fn parse(chain: &[&Node], doc: &mut Document) -> Option<Self> {
        let (mut offset, mut extent, mut rotation) = (None, None, 0.0);
        for shape in chain {
            let transform = match shape.name.as_str() {
                "graphicFrame" => shape.child("xfrm"),
                _ => shape.child("spPr").and_then(|p| p.child("xfrm")),
            };
            let Some(transform) = transform else { continue };
            if let Some(off) = transform.child("off") {
                offset = Some((points(off.attr("x"))?, points(off.attr("y"))?));
            }
            if let Some(ext) = transform.child("ext") {
                extent = Some((points(ext.attr("cx"))?, points(ext.attr("cy"))?));
            }
            rotation = (number(transform.attr("rot"), 0.0) / 60000.0) % 360.0;
            if flag(transform.attr("flipH"), false) || flag(transform.attr("flipV"), false) {
                doc.warn("PPTX shape flips are not supported.");
            }
        }
        let (x, y) = offset?;
        let (width, height) = extent?;
        if width < 0.0
            || height < 0.0
            || (x + width).abs() > MAX_POINT
            || (y + height).abs() > MAX_POINT
        {
            return None;
        }
        Some(Self {
            x,
            y,
            width,
            height,
            rotation,
        })
    }

    pub fn element(self, kind: ElementType) -> Element {
        Element {
            kind,
            x: self.x,
            y: self.y,
            width: self.width,
            height: self.height,
            rotation: self.rotation,
            ..Element::default()
        }
    }

    pub fn text_element(self, chain: &[&Node], doc: &mut Document) -> Option<Element> {
        let (mut left, mut right, mut top, mut bottom) = (7.2, 7.2, 3.6, 3.6);
        for shape in chain {
            let Some(body) = shape.child("txBody").and_then(|b| b.child("bodyPr")) else {
                continue;
            };
            left = points(body.attr("lIns")).map_or(left, |v| v.max(0.0));
            right = points(body.attr("rIns")).map_or(right, |v| v.max(0.0));
            top = points(body.attr("tIns")).map_or(top, |v| v.max(0.0));
            bottom = points(body.attr("bIns")).map_or(bottom, |v| v.max(0.0));
            if !matches!(body.attr("anchor"), "" | "t") {
                doc.warn("PPTX vertical text alignment uses top alignment.");
            }
            if !matches!(body.attr("vert"), "" | "horz") || number(body.attr("rot"), 0.0) != 0.0 {
                doc.warn("PPTX vertical text and independent text rotation are not supported.");
            }
            if !matches!(body.attr("numCol"), "" | "1") || body.attr("wrap") == "none" {
                doc.warn("PPTX text uses one wrapped column.");
            }
            if ["normAutofit", "spAutoFit", "prstTxWarp"]
                .iter()
                .any(|name| body.child(name).is_some())
            {
                doc.warn("PPTX text autofit and WordArt are not supported.");
            }
        }
        left = left.min(self.width);
        right = right.min(self.width - left);
        top = top.min(self.height);
        bottom = bottom.min(self.height - top);
        let width = self.width - left - right;
        let height = self.height - top - bottom;
        if width <= 0.0 || height <= 0.0 {
            return None;
        }
        let (sin, cos) = self.rotation.to_radians().sin_cos();
        let (dx, dy) = ((left - right) / 2.0, (top - bottom) / 2.0);
        let x = self.x + (self.width - width) / 2.0 + dx * cos - dy * sin;
        let y = self.y + (self.height - height) / 2.0 + dx * sin + dy * cos;
        if [x, y, x + width, y + height]
            .iter()
            .any(|v| !v.is_finite() || v.abs() > MAX_POINT)
        {
            return None;
        }
        Some(Element {
            x,
            y,
            width,
            height,
            padding: 0.0,
            ..self.element(ElementType::TEXT)
        })
    }
}
