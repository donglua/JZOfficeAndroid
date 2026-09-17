use crate::xml::Node;
use crate::{Package, Result};
use std::collections::{HashMap, VecDeque};
use std::rc::Rc;

pub(super) struct Part {
    pub path: String,
    pub root: Node,
    pub rels: HashMap<String, String>,
}

impl Part {
    pub fn read(pkg: &mut Package, path: &str) -> Result<Self> {
        Ok(Self {
            path: path.to_owned(),
            root: pkg.xml(path)?,
            rels: pkg.relationships(path)?,
        })
    }
}

struct Entry {
    part: Rc<Part>,
    nodes: usize,
    bytes: usize,
}

#[derive(Default)]
pub(super) struct Cache {
    entries: VecDeque<Entry>,
    nodes: usize,
    bytes: usize,
}

impl Cache {
    pub fn related(
        &mut self,
        pkg: &mut Package,
        source: Option<&Part>,
        kind: &str,
    ) -> Result<Option<Rc<Part>>> {
        let Some(source) = source else {
            return Ok(None);
        };
        let Some(path) = pkg.related_by_type(&source.path, kind)? else {
            return Ok(None);
        };
        if let Some(entry) = self.entries.iter().find(|e| e.part.path == path) {
            return Ok(Some(Rc::clone(&entry.part)));
        }
        let part = Rc::new(Part::read(pkg, &path)?);
        let (nodes, mut bytes) = graph_size(&part.root);
        bytes = bytes.saturating_add(part.path.capacity());
        for (key, value) in &part.rels {
            bytes = bytes.saturating_add(128 + key.capacity() + value.capacity());
        }
        if nodes <= 100_000 && bytes <= 16 * 1024 * 1024 {
            while self.entries.len() >= 16
                || self.nodes + nodes > 100_000
                || self.bytes + bytes > 16 * 1024 * 1024
            {
                if let Some(old) = self.entries.pop_front() {
                    self.nodes -= old.nodes;
                    self.bytes -= old.bytes;
                } else {
                    break;
                }
            }
            self.nodes += nodes;
            self.bytes += bytes;
            self.entries.push_back(Entry {
                part: Rc::clone(&part),
                nodes,
                bytes,
            });
        }
        Ok(Some(part))
    }
}

fn graph_size(node: &Node) -> (usize, usize) {
    let mut count = 1_usize;
    let mut bytes = 256_usize
        .saturating_add(node.name.capacity())
        .saturating_add(node.text.capacity());
    bytes = bytes.saturating_add(
        node.children
            .capacity()
            .saturating_mul(std::mem::size_of::<Node>()),
    );
    bytes = bytes.saturating_add(node.attrs.capacity().saturating_mul(128));
    for (key, value) in &node.attrs {
        bytes = bytes
            .saturating_add(key.capacity())
            .saturating_add(value.capacity());
    }
    for child in &node.children {
        let (child_count, child_bytes) = graph_size(child);
        count = count.saturating_add(child_count);
        bytes = bytes.saturating_add(child_bytes);
    }
    (count, bytes)
}
