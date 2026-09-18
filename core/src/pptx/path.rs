use crate::model::{Path, PathCommand};
use crate::xml::Node;

pub(super) fn parse(properties: &Node, size: [f32; 2]) -> Option<Vec<Path>> {
    let list = properties.child("custGeom")?.child("pathLst")?;
    if list.children.is_empty() || list.children.len() > 32 {
        return None;
    }
    let mut paths = Vec::new();
    let mut total = 0;
    for path in &list.children {
        if path.name != "path" || !matches!(path.attr("fill"), "" | "norm" | "none") {
            return None;
        }
        let width = path.attr("w").parse::<f32>().ok()?;
        let height = path.attr("h").parse::<f32>().ok()?;
        if !width.is_finite() || !height.is_finite() || width <= 0.0 || height <= 0.0 {
            return None;
        }
        total += path.children.len();
        if total > 10_000 || path.children.first()?.name != "moveTo" {
            return None;
        }
        let scale = [size[0] / width, size[1] / height];
        let mut commands = Vec::new();
        for command in &path.children {
            commands.push(match command.name.as_str() {
                "moveTo" => PathCommand::Move(points(command, scale)?),
                "lnTo" => PathCommand::Line(points(command, scale)?),
                "quadBezTo" => PathCommand::Quad(points(command, scale)?),
                "cubicBezTo" => PathCommand::Cubic(points(command, scale)?),
                "close" if command.children.is_empty() => PathCommand::Close,
                _ => return None,
            });
        }
        paths.push(Path {
            fill: path.attr("fill") != "none",
            stroke: !matches!(path.attr("stroke"), "0" | "false"),
            commands,
        });
    }
    Some(paths)
}

fn points<const N: usize>(command: &Node, scale: [f32; 2]) -> Option<[f32; N]> {
    if command.children.len() * 2 != N {
        return None;
    }
    let mut result = [0.0; N];
    for (point, pair) in command.children.iter().zip(result.chunks_exact_mut(2)) {
        if point.name != "pt" {
            return None;
        }
        for (axis, attr) in ["x", "y"].iter().enumerate() {
            let value = point.attr(attr).parse::<f32>().ok()? * scale[axis];
            if !value.is_finite() || value.abs() > 100_000.0 {
                return None;
            }
            pair[axis] = value;
        }
    }
    Some(result)
}
