use super::colors::Theme;
use super::geometry::Bounds;
use super::inheritance;
use super::parts::Part;
use super::styles::Styles;
use super::text::Text;
use super::{flag, relation_id, Budget};
use crate::model::{Document, Element, ElementType, Page, Paragraph};
use crate::xml::Node;
use crate::{Package, Result};

pub(super) struct Render<'a> {
    pub doc: &'a mut Document,
    pub theme: &'a Theme,
    pub pkg: &'a Package,
    pub budget: &'a mut Budget,
    pub defaults: Option<&'a Node>,
    pub layout: Option<&'a Node>,
    pub master: Option<&'a Node>,
}

impl Render<'_> {
    pub fn background(&mut self, page: &mut Page, sources: [&Node; 3]) {
        for root in sources {
            let Some(bg) = root.child("cSld").and_then(|n| n.child("bg")) else {
                continue;
            };
            if let Some(properties) = bg.child("bgPr") {
                page.background = self.theme.fill(properties, page.background, self.doc);
            } else if let Some(reference) = bg.child("bgRef") {
                page.background = self
                    .theme
                    .fill_reference(reference, page.background, self.doc);
            }
            return;
        }
    }

    pub fn tree(&mut self, part: &Part, inherited: bool, page: &mut Page) -> Result<()> {
        unsupported(&part.root, self.doc);
        let Some(tree) = inheritance::tree(&part.root) else {
            return Ok(());
        };
        for shape in &tree.children {
            if inherited && inheritance::placeholder(shape).is_some() {
                continue;
            }
            let hidden = shape
                .children
                .iter()
                .find(|n| n.name.starts_with("nv"))
                .and_then(|n| n.child("cNvPr"))
                .is_some_and(|n| flag(n.attr("hidden"), false));
            if hidden {
                continue;
            }
            match shape.name.as_str() {
                "nvGrpSpPr" | "grpSpPr" | "extLst" | "grpSp" => continue,
                "sp" | "cxnSp" | "pic" | "graphicFrame" => (),
                _ => {
                    self.doc.warn("Unsupported PPTX shape content was omitted.");
                    continue;
                }
            }
            let chain = if inherited {
                vec![shape]
            } else {
                inheritance::chain(shape, self.layout, self.master)
            };
            let Some(bounds) = Bounds::parse(&chain, self.doc) else {
                self.doc
                    .warn("PPTX shapes with missing or invalid absolute bounds were omitted.");
                continue;
            };
            match shape.name.as_str() {
                "pic" => {
                    if let Some(element) = self.picture(part, shape, bounds) {
                        self.emit(page, element)?;
                    }
                }
                "graphicFrame" => {
                    if let Some(element) = self.graphic(shape, bounds)? {
                        self.emit(page, element)?;
                    }
                }
                _ => {
                    let mut element = bounds.element(ElementType::RECT);
                    let supported = Styles {
                        doc: self.doc,
                        theme: self.theme,
                    }
                    .drawing(&chain, &mut element);
                    if supported
                        && (element.fill != 0
                            || element.stroke != 0
                            || shape.child("txBody").is_none())
                    {
                        self.emit(page, element)?;
                    }
                    if let Some(body) = shape.child("txBody") {
                        if let Some(mut text) = bounds.text_element(&chain, self.doc) {
                            text.paragraphs = self.paragraphs(body, &chain)?;
                            if !text.paragraphs.is_empty() {
                                self.emit(page, text)?;
                            }
                        } else {
                            self.doc
                                .warn("PPTX text with empty or excessive bounds was omitted.");
                        }
                    }
                }
            }
        }
        Ok(())
    }

    fn picture(&mut self, part: &Part, shape: &Node, bounds: Bounds) -> Option<Element> {
        let fill = shape.child("blipFill");
        let blip = fill.and_then(|n| n.child("blip"));
        let path = blip.and_then(|n| part.rels.get(relation_id(n, "embed")));
        let Some(path) = path.filter(|p| self.pkg.has(p)) else {
            self.doc
                .warn("PPTX linked or missing pictures were omitted.");
            return None;
        };
        if fill.is_some_and(|n| n.child("srcRect").is_some() || n.child("tile").is_some())
            || blip.is_some_and(|n| !n.children.is_empty())
        {
            self.doc
                .warn("PPTX picture cropping, tiling, and effects are not supported.");
        }
        if let Some(properties) = shape.child("spPr") {
            if properties.child("custGeom").is_some()
                || properties
                    .child("prstGeom")
                    .is_some_and(|n| n.attr("prst") != "rect")
            {
                self.doc.warn("PPTX picture masks use rectangular bounds.");
            }
        }
        let mut element = bounds.element(ElementType::IMAGE);
        element.image = Some(path.clone());
        Some(element)
    }

    pub fn paragraphs(&mut self, body: &Node, chain: &[&Node]) -> Result<Vec<Paragraph>> {
        Text {
            doc: self.doc,
            theme: self.theme,
            budget: self.budget,
            defaults: self.defaults,
            master: self.master,
            chain,
        }
        .read(body)
    }

    fn emit(&mut self, page: &mut Page, element: Element) -> Result<()> {
        self.budget.object()?;
        page.elements.push(element);
        Ok(())
    }
}

fn unsupported(node: &Node, doc: &mut Document) {
    match node.name.as_str() {
        "grpSp" => doc.warn("PPTX grouped shapes are omitted."),
        "chart" => doc.warn("PPTX charts are omitted."),
        "relIds" => doc.warn("PPTX SmartArt is omitted."),
        "oMath" | "oMathPara" => doc.warn("PPTX mathematical equations are omitted."),
        "timing" => doc.warn("PPTX animations and timing are not supported."),
        "transition" => doc.warn("PPTX transitions are not supported."),
        "AlternateContent" => doc.warn("PPTX alternate content is omitted."),
        "audioFile" | "videoFile" | "media" | "oleObj" => {
            doc.warn("PPTX multimedia and embedded objects are not supported.")
        }
        "effectLst" | "effectDag" | "scene3d" | "sp3d" => {
            if !node.children.is_empty() {
                doc.warn("PPTX shape effects and 3D styling are not supported.");
            }
        }
        _ => (),
    }
    for child in &node.children {
        unsupported(child, doc);
    }
}
