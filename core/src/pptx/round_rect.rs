use crate::model::{Path, PathCommand};
use crate::xml::Node;

pub(super) fn radius(geometry: &Node, size: [f32; 2]) -> Option<f32> {
    let adjustment = geometry
        .child("avLst")
        .and_then(|list| list.named("gd").find(|guide| guide.attr("name") == "adj"));
    let value = match adjustment {
        Some(guide) => {
            let mut formula = guide.attr("fmla").split_whitespace();
            if formula.next()? != "val" {
                return None;
            }
            let value = formula.next()?.parse::<i32>().ok()?;
            if formula.next().is_some() {
                return None;
            }
            value
        }
        None => 16667,
    };
    let adjustment = u16::try_from(value.clamp(0, 50000)).ok()?;
    Some(size[0].min(size[1]) * f32::from(adjustment) / 100000.0)
}

pub(super) fn path([width, height]: [f32; 2], radius: f32) -> Path {
    let control = radius * 0.552_284_8;
    Path {
        fill: true,
        stroke: true,
        commands: vec![
            PathCommand::Move([0.0, radius]),
            PathCommand::Cubic([0.0, radius - control, radius - control, 0.0, radius, 0.0]),
            PathCommand::Line([width - radius, 0.0]),
            PathCommand::Cubic([
                width - radius + control,
                0.0,
                width,
                radius - control,
                width,
                radius,
            ]),
            PathCommand::Line([width, height - radius]),
            PathCommand::Cubic([
                width,
                height - radius + control,
                width - radius + control,
                height,
                width - radius,
                height,
            ]),
            PathCommand::Line([radius, height]),
            PathCommand::Cubic([
                radius - control,
                height,
                0.0,
                height - radius + control,
                0.0,
                height - radius,
            ]),
            PathCommand::Close,
        ],
    }
}
