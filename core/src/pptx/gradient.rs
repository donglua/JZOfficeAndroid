use super::colors::Theme;
use super::flag;
use crate::model::{Document, GradientFill};
use crate::xml::Node;

impl Theme {
    pub fn gradient(&self, properties: &Node, doc: &mut Document) -> Option<GradientFill> {
        let gradient = properties.child("gradFill")?;
        let linear = gradient.child("lin")?;
        if gradient.child("path").is_some() {
            return None;
        }
        let stops = gradient.child("gsLst")?;
        if !(2..=16).contains(&stops.children.len()) {
            return None;
        }
        let mut values = Vec::new();
        for stop in &stops.children {
            if stop.name != "gs" {
                return None;
            }
            let position = stop.attr("pos").parse::<f32>().ok()? / 100_000.0;
            if !position.is_finite() || !(0.0..=1.0).contains(&position) {
                return None;
            }
            values.push((position, self.color(stop, 0xff202124, doc)));
        }
        values.sort_by(|a, b| a.0.total_cmp(&b.0));
        let angle = match linear.attr("ang") {
            "" => 0.0,
            value => value.parse::<f32>().ok()? / 60_000.0,
        };
        if !angle.is_finite() {
            return None;
        }
        if gradient.child("tileRect").is_some()
            || !matches!(gradient.attr("flip"), "" | "none")
            || !flag(gradient.attr("rotWithShape"), true)
        {
            doc.warn("PPTX gradient tiling and independent rotation are simplified.");
        }
        Some(GradientFill {
            colors: values.iter().map(|(_, color)| *color).collect(),
            positions: values.iter().map(|(position, _)| *position).collect(),
            points: [0.0; 4],
            transform: None,
            angle: angle % 360.0,
            scaled: flag(linear.attr("scaled"), true),
            in_slide_space: false,
        })
    }
}
