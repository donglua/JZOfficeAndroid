use crate::{model::Document, xml::Node};

pub(super) fn warn(node: &Node, doc: &mut Document) {
    match node.name.as_str() {
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
        warn(child, doc);
    }
}
