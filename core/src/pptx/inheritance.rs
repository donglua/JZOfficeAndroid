use crate::xml::Node;

pub(super) fn placeholder(shape: &Node) -> Option<&Node> {
    shape
        .children
        .iter()
        .find(|n| n.name.starts_with("nv"))?
        .child("nvPr")?
        .child("ph")
}

pub(super) fn tree(root: &Node) -> Option<&Node> {
    root.child("cSld")?.child("spTree")
}

pub(super) fn chain<'a>(
    shape: &'a Node,
    layout: Option<&'a Node>,
    master: Option<&'a Node>,
) -> Vec<&'a Node> {
    let layout_shape = layout.and_then(|root| matching(root, shape, false));
    let master_shape = master.and_then(|root| matching(root, layout_shape.unwrap_or(shape), true));
    master_shape
        .into_iter()
        .chain(layout_shape)
        .chain(Some(shape))
        .collect()
}

fn matching<'a>(root: &'a Node, shape: &Node, by_type: bool) -> Option<&'a Node> {
    let ph = placeholder(shape)?;
    let candidates = tree(root)?;
    let kind = match ph.attr("type") {
        "" => "obj",
        "ctrTitle" if by_type => "title",
        value => value,
    };
    let mut fallback = None;
    for candidate in &candidates.children {
        let Some(other) = placeholder(candidate) else {
            continue;
        };
        let other_kind = if other.attr("type").is_empty() {
            "obj"
        } else {
            other.attr("type")
        };
        if by_type && kind == other_kind {
            return Some(candidate);
        }
        if !by_type && index(ph) == index(other) {
            return Some(candidate);
        }
        if by_type && matches!(kind, "obj" | "subTitle") && other_kind == "body" {
            fallback = Some(candidate);
        }
    }
    fallback
}

fn index(ph: &Node) -> &str {
    if ph.attr("idx").is_empty() {
        "0"
    } else {
        ph.attr("idx")
    }
}

pub(super) fn text_style(chain: &[&Node]) -> &'static str {
    let mut placeholder_found = false;
    for shape in chain.iter().rev() {
        if let Some(ph) = placeholder(shape) {
            placeholder_found = true;
            match ph.attr("type") {
                "title" | "ctrTitle" => return "titleStyle",
                "body" | "obj" | "subTitle" => return "bodyStyle",
                "" => (),
                _ => return "otherStyle",
            }
        }
    }
    if placeholder_found {
        "bodyStyle"
    } else {
        "otherStyle"
    }
}

pub(super) fn level_defaults<'a>(list: Option<&'a Node>, level: usize, out: &mut Vec<&'a Node>) {
    if let Some(list) = list {
        if let Some(default) = list.child("defPPr") {
            out.push(default);
        }
        if let Some(specific) = list.child(&format!("lvl{}pPr", level + 1)) {
            out.push(specific);
        }
    }
}
