use crate::model::{Path, PathCommand};
use crate::xml::Node;

pub(super) fn path(geometry: &Node, [width, height]: [f32; 2]) -> Option<Path> {
    let commands = match geometry.attr("prst") {
        "triangle" => {
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
                None => 50_000,
            };
            // ECMA-376 triangle clamps adj to 0..100000, exactly representable in f32.
            let adjustment = value.clamp(0, 100_000) as f32 / 100_000.0;
            vec![
                PathCommand::Move([0.0, height]),
                PathCommand::Line([width * adjustment, 0.0]),
                PathCommand::Line([width, height]),
                PathCommand::Close,
            ]
        }
        "rtTriangle" => vec![
            PathCommand::Move([0.0, height]),
            PathCommand::Line([0.0, 0.0]),
            PathCommand::Line([width, height]),
            PathCommand::Close,
        ],
        "diamond" => vec![
            PathCommand::Move([0.0, height / 2.0]),
            PathCommand::Line([width / 2.0, 0.0]),
            PathCommand::Line([width, height / 2.0]),
            PathCommand::Line([width / 2.0, height]),
            PathCommand::Close,
        ],
        _ => return None,
    };
    Some(Path {
        fill: true,
        stroke: true,
        commands,
    })
}
