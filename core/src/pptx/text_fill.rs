use super::colors::Theme;
use crate::model::Document;
use crate::xml::Node;

impl Theme {
    pub fn text_fill(&self, properties: &Node, fallback: u32, doc: &mut Document) -> u32 {
        if properties.child("noFill").is_some() || properties.child("solidFill").is_some() {
            return self.fill(properties, fallback, doc);
        }
        let Some(gradient) = properties.child("gradFill") else {
            return self.fill(properties, fallback, doc);
        };
        let mut before: Option<(u32, &Node)> = None;
        let mut after: Option<(u32, &Node)> = None;
        if let Some(stops) = gradient.child("gsLst") {
            for stop in stops.named("gs") {
                let Ok(position) = stop.attr("pos").parse::<u32>() else {
                    continue;
                };
                if position > 100_000 {
                    continue;
                }
                if position <= 50_000 && before.is_none_or(|(p, _)| position >= p) {
                    before = Some((position, stop));
                }
                if position >= 50_000 && after.is_none_or(|(p, _)| position <= p) {
                    after = Some((position, stop));
                }
            }
        }
        doc.warn("PPTX gradient text uses a solid approximation of its midpoint color.");
        match (before, after) {
            (Some((start, left)), Some((end, right))) if start < end => {
                let left = self.color(left, fallback, doc);
                let right = self.color(right, fallback, doc);
                interpolate(
                    left,
                    right,
                    f64::from(50_000 - start) / f64::from(end - start),
                )
            }
            (Some((_, stop)), _) | (_, Some((_, stop))) => self.color(stop, fallback, doc),
            _ => fallback,
        }
    }
}

fn interpolate(left: u32, right: u32, fraction: f64) -> u32 {
    let mix = |a: f64, b: f64| a + (b - a) * fraction;
    let left_alpha = f64::from(left >> 24);
    let right_alpha = f64::from(right >> 24);
    let alpha = mix(left_alpha, right_alpha);
    if alpha == 0.0 {
        return 0;
    }
    let mut result = (alpha.round() as u32) << 24;
    for shift in [16, 8, 0] {
        let channel = mix(
            f64::from((left >> shift) & 255) * left_alpha,
            f64::from((right >> shift) & 255) * right_alpha,
        ) / alpha;
        result |= (channel.round() as u32) << shift;
    }
    result
}
