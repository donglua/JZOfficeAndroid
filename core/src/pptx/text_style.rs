use super::colors::Theme;
use super::flag;
use super::geometry::points;
use crate::model::{Document, Paragraph, Run};
use crate::xml::{number, Node};

pub(super) struct TextStyle<'a> {
    pub doc: &'a mut Document,
    pub theme: &'a Theme,
}

impl TextStyle<'_> {
    pub fn run(&mut self, run: &mut Run, properties: Option<&Node>) {
        let Some(properties) = properties else { return };
        if !properties.attr("sz").is_empty() {
            run.size = (number(properties.attr("sz"), run.size * 100.0) / 100.0).clamp(1.0, 400.0);
        }
        run.bold = flag(properties.attr("b"), run.bold);
        run.italic = flag(properties.attr("i"), run.italic);
        match properties.attr("u") {
            "" => (),
            "none" | "0" | "false" => run.underline = false,
            "sng" => run.underline = true,
            _ => {
                run.underline = true;
                self.doc
                    .warn("PPTX underline styles use single underlines.");
            }
        }
        run.color = self.theme.fill(properties, run.color, self.doc);
        if ["latin", "ea", "cs", "sym"]
            .iter()
            .any(|n| properties.child(n).is_some())
        {
            self.doc
                .warn("PPTX font faces use the viewer's default typeface.");
        }
        if number(properties.attr("baseline"), 0.0) != 0.0
            || !matches!(properties.attr("strike"), "" | "noStrike")
            || properties.child("highlight").is_some()
        {
            self.doc.warn(
                "PPTX baseline shifts, strikethrough, and text highlighting are not supported.",
            );
        }
        if properties.child("hlinkClick").is_some() || properties.child("hlinkMouseOver").is_some()
        {
            self.doc.warn("PPTX text hyperlinks are not interactive.");
        }
    }

    pub fn paragraph(&mut self, paragraph: &mut Paragraph, properties: &Node) {
        match properties.attr("algn") {
            "" => (),
            "l" => paragraph.alignment = 0,
            "ctr" => paragraph.alignment = 1,
            "r" => paragraph.alignment = 2,
            _ => {
                paragraph.alignment = 0;
                self.doc
                    .warn("PPTX justified paragraphs use left alignment.");
            }
        }
        if let Some(indent) = points(properties.attr("marL")) {
            paragraph.indent = indent.max(0.0);
        }
        paragraph.before = self.spacing(properties.child("spcBef"), paragraph.before);
        paragraph.after = self.spacing(properties.child("spcAft"), paragraph.after);
        if properties.child("lnSpc").is_some()
            || properties.child("tabLst").is_some()
            || number(properties.attr("indent"), 0.0) != 0.0
            || flag(properties.attr("rtl"), false)
        {
            self.doc.warn(
                "PPTX custom line spacing, tabs, hanging indents, and RTL layout are simplified.",
            );
        }
    }

    fn spacing(&mut self, node: Option<&Node>, fallback: f32) -> f32 {
        let Some(node) = node else { return fallback };
        if let Some(points) = node.child("spcPts") {
            return (number(points.attr("val"), fallback * 100.0) / 100.0).clamp(0.0, 10_000.0);
        }
        if node.child("spcPct").is_some() {
            self.doc
                .warn("PPTX percentage paragraph spacing is not supported.");
        }
        fallback
    }
}
