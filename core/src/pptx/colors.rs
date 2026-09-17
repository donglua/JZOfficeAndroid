use super::parts::Part;
use crate::model::Document;
use crate::xml::{self, Node};
use std::collections::HashMap;
use std::rc::Rc;

pub(super) struct Theme {
    source: Option<Rc<Part>>,
    colors: HashMap<String, u32>,
    mapping: HashMap<String, String>,
}

impl Theme {
    pub fn new(source: Option<Rc<Part>>, doc: &mut Document) -> Self {
        let mut result = Self {
            source: source.clone(),
            colors: HashMap::new(),
            mapping: HashMap::new(),
        };
        result.colors.insert("dk1".to_owned(), 0xff000000);
        result.colors.insert("lt1".to_owned(), 0xffffffff);
        for (key, value) in [
            ("tx1", "dk1"),
            ("bg1", "lt1"),
            ("tx2", "dk2"),
            ("bg2", "lt2"),
        ] {
            result.mapping.insert(key.to_owned(), value.to_owned());
        }
        if let Some(scheme) = source
            .as_deref()
            .and_then(|p| p.root.child("themeElements"))
            .and_then(|n| n.child("clrScheme"))
        {
            for entry in &scheme.children {
                let color = result.color(entry, 0xff202124, doc);
                result.colors.insert(entry.name.clone(), color);
            }
        }
        result
    }

    pub fn set_mapping(&mut self, slide: &Node, layout: Option<&Node>, master: Option<&Node>) {
        self.map(master.and_then(|n| n.child("clrMap")));
        let slide_override = slide.child("clrMapOvr");
        if slide_override
            .and_then(|n| n.child("masterClrMapping"))
            .is_none()
        {
            self.map(
                layout
                    .and_then(|n| n.child("clrMapOvr"))
                    .and_then(|n| n.child("overrideClrMapping")),
            );
        }
        self.map(slide_override.and_then(|n| n.child("overrideClrMapping")));
    }

    fn map(&mut self, node: Option<&Node>) {
        if let Some(node) = node {
            self.mapping.extend(node.attrs.clone());
        }
    }

    fn resolve(&self, key: &str) -> Option<u32> {
        self.colors
            .get(self.mapping.get(key).map_or(key, String::as_str))
            .copied()
    }

    pub fn text_color(&self) -> u32 {
        self.resolve("tx1").unwrap_or(0xff202124)
    }

    pub fn color(&self, parent: &Node, fallback: u32, doc: &mut Document) -> u32 {
        for node in &parent.children {
            let value = match node.name.as_str() {
                "srgbClr" => xml::color(node.attr("val"), fallback),
                "sysClr" => xml::color(node.attr("lastClr"), fallback),
                "schemeClr" => match self.resolve(node.attr("val")) {
                    Some(value) => value,
                    None => {
                        if node.attr("val") != "phClr" {
                            doc.warn("Some PPTX theme colors could not be resolved.");
                        }
                        fallback
                    }
                },
                "prstClr" | "hslClr" | "scrgbClr" => {
                    doc.warn("PPTX preset, HSL, and scRGB colors are not supported.");
                    return fallback;
                }
                _ => continue,
            };
            return transforms(value, node, doc);
        }
        fallback
    }

    pub fn fill(&self, properties: &Node, fallback: u32, doc: &mut Document) -> u32 {
        if properties.child("noFill").is_some() {
            return 0;
        }
        if let Some(solid) = properties.child("solidFill") {
            return self.color(solid, fallback, doc);
        }
        if ["gradFill", "pattFill", "blipFill", "grpFill"]
            .iter()
            .any(|n| properties.child(n).is_some())
        {
            doc.warn("PPTX gradient, pattern, picture, and group fills are not supported.");
        }
        fallback
    }

    pub fn format_list(&self, name: &str) -> Option<&Node> {
        self.source
            .as_deref()?
            .root
            .child("themeElements")?
            .child("fmtScheme")?
            .child(name)
    }

    pub fn fill_reference(&self, reference: &Node, fallback: u32, doc: &mut Document) -> u32 {
        let index = reference.attr("idx").parse::<usize>().unwrap_or(0);
        let (list, offset) = match index {
            0 | 1000 => return 0,
            1001.. => ("bgFillStyleLst", index - 1001),
            _ => ("fillStyleLst", index - 1),
        };
        let placeholder = self.color(reference, fallback, doc);
        if let Some(style) = self.format_list(list).and_then(|n| n.children.get(offset)) {
            match style.name.as_str() {
                "solidFill" => return self.color(style, placeholder, doc),
                "noFill" => return 0,
                _ => (),
            }
        }
        doc.warn("Some PPTX theme fill styles are not supported.");
        fallback
    }
}

fn transforms(value: u32, node: &Node, doc: &mut Document) -> u32 {
    let mut channels = [(value >> 16) & 255, (value >> 8) & 255, value & 255];
    let mut alpha = value >> 24;
    for transform in &node.children {
        let amount = transform
            .attr("val")
            .parse::<u32>()
            .unwrap_or(100_000)
            .min(100_000);
        match transform.name.as_str() {
            "alpha" => alpha = (255 * amount + 50_000) / 100_000,
            "alphaMod" => alpha = (alpha * amount + 50_000) / 100_000,
            "shade" => {
                for channel in &mut channels {
                    *channel = (*channel * amount + 50_000) / 100_000;
                }
            }
            "tint" => {
                for channel in &mut channels {
                    *channel = 255 - ((255 - *channel) * amount + 50_000) / 100_000;
                }
            }
            _ => doc.warn("Some PPTX color transforms are not supported."),
        }
    }
    let [red, green, blue] = channels;
    (alpha << 24) | (red << 16) | (green << 8) | blue
}
