use super::{flag, geometry::points};
use crate::{
    model::Element,
    xml::{number, Node},
};

/// Maps child coordinates to slide coordinates: x' = ax + cy + e, y' = bx + dy + f.
#[derive(Clone, Copy)]
pub(super) struct Transform(pub [f32; 6]);

impl Transform {
    pub const IDENTITY: Self = Self([1.0, 0.0, 0.0, 1.0, 0.0, 0.0]);

    pub fn group(self, group: &Node) -> Option<Self> {
        let node = group.child("grpSpPr")?.child("xfrm")?;
        let off = node.child("off")?;
        let ext = node.child("ext")?;
        let child_off = node.child("chOff")?;
        let child_ext = node.child("chExt")?;
        let (x, y) = (points(off.attr("x"))?, points(off.attr("y"))?);
        let (w, h) = (points(ext.attr("cx"))?, points(ext.attr("cy"))?);
        let (cx, cy) = (points(child_off.attr("x"))?, points(child_off.attr("y"))?);
        let (cw, ch) = (points(child_ext.attr("cx"))?, points(child_ext.attr("cy"))?);
        if w <= 0.0 || h <= 0.0 || cw <= 0.0 || ch <= 0.0 {
            return None;
        }
        let (sx, sy) = (w / cw, h / ch);
        let angle = (number(node.attr("rot"), 0.0) / 60000.0) % 360.0;
        let (sin, cos) = angle.to_radians().sin_cos();
        let (dx, dy) = (-cx * sx - w / 2.0, -cy * sy - h / 2.0);
        let child = [
            cos * sx,
            sin * sx,
            -sin * sy,
            cos * sy,
            x + w / 2.0 + cos * dx - sin * dy,
            y + h / 2.0 + sin * dx + cos * dy,
        ];
        let [a, b, c, d, e, f] = self.0;
        let [g, h, i, j, k, l] = child;
        let combined = [
            a * g + c * h,
            b * g + d * h,
            a * i + c * j,
            b * i + d * j,
            a * k + c * l + e,
            b * k + d * l + f,
        ];
        combined
            .iter()
            .all(|v| v.is_finite() && v.abs() <= 100_000.0)
            .then_some(Self(combined))
    }

    pub fn accepts(self, element: &Element) -> bool {
        let [a, b, c, d, e, f] = self.0;
        let (sin, cos) = element.rotation.to_radians().sin_cos();
        let (hw, hh) = (element.width / 2.0, element.height / 2.0);
        [(-hw, -hh), (hw, -hh), (-hw, hh), (hw, hh)]
            .iter()
            .all(|&(dx, dy)| {
                let x = element.x + hw + cos * dx - sin * dy;
                let y = element.y + hh + sin * dx + cos * dy;
                [a * x + c * y + e, b * x + d * y + f]
                    .iter()
                    .all(|v| v.is_finite() && v.abs() <= 100_000.0)
            })
    }
}

pub(super) fn flipped(group: &Node) -> bool {
    group
        .child("grpSpPr")
        .and_then(|p| p.child("xfrm"))
        .is_some_and(|n| flag(n.attr("flipH"), false) || flag(n.attr("flipV"), false))
}
