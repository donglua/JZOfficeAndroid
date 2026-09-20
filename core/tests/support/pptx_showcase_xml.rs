use super::package::TestResult;
use roxmltree::Document;
use std::ops::Range;

pub const WIDTH: u32 = 12_192_000;
pub const HEIGHT: u32 = 6_858_000;

#[derive(Clone, Copy)]
pub struct Frame {
    width: u32,
    height: u32,
    numerator: u32,
    denominator: u32,
    viewport: [u32; 4],
}

impl Frame {
    pub fn read(presentation: &str, annotated: bool) -> TestResult<Self> {
        let document = Document::parse(presentation)?;
        let size = document
            .descendants()
            .find(|node| node.has_tag_name("sldSz"))
            .ok_or("source presentation has no size")?;
        let width = size
            .attribute("cx")
            .ok_or("source width missing")?
            .parse()?;
        let height = size
            .attribute("cy")
            .ok_or("source height missing")?
            .parse()?;
        if width == 0 || height == 0 {
            return Err("source slide has zero size".into());
        }
        let viewport = if annotated {
            [304_800, 1_117_600, 8_432_800, 4_743_450]
        } else {
            [0, 0, WIDTH, HEIGHT]
        };
        let [_, _, target_width, target_height] = viewport;
        let (numerator, denominator) = if u64::from(target_width) * u64::from(height)
            <= u64::from(target_height) * u64::from(width)
        {
            (target_width, width)
        } else {
            (target_height, height)
        };
        Ok(Self {
            width,
            height,
            numerator,
            denominator,
            viewport,
        })
    }

    fn scaled(self, value: i64) -> i64 {
        let numerator = i64::from(self.numerator);
        let denominator = i64::from(self.denominator);
        value.signum() * ((value.abs() * numerator + denominator / 2) / denominator)
    }

    pub fn normalize(self, source: &str) -> TestResult<String> {
        let document = Document::parse(source)?;
        let mut edits = Vec::new();
        for node in document.descendants().filter(|node| node.is_element()) {
            let name = node.tag_name().name();
            for attribute in node.attributes() {
                let scale = match attribute.name() {
                    "x" | "y" | "cx" | "cy" => matches!(name, "off" | "ext" | "chOff" | "chExt"),
                    "sz" | "kern" | "spc" => matches!(name, "rPr" | "defRPr" | "endParaRPr"),
                    "lIns" | "rIns" | "tIns" | "bIns" => name == "bodyPr",
                    "marL" | "marR" | "indent" => name.ends_with("pPr"),
                    "val" => name == "spcPts",
                    "w" => matches!(name, "ln" | "gridCol"),
                    "h" => name == "tr",
                    _ => false,
                };
                if scale {
                    edits.push((
                        attribute.range_value(),
                        self.scaled(attribute.value().parse()?).to_string(),
                    ));
                }
            }
            if name == "bodyPr" {
                let mut defaults = String::new();
                for (attribute, value) in [
                    ("lIns", 91440),
                    ("rIns", 91440),
                    ("tIns", 45720),
                    ("bIns", 45720),
                ] {
                    if node.attribute(attribute).is_none() {
                        defaults.push_str(&format!(r#" {attribute}="{}""#, self.scaled(value)));
                    }
                }
                let position = node.range().start
                    + source[node.range()]
                        .find("bodyPr")
                        .ok_or("bodyPr tag missing")?
                    + "bodyPr".len();
                edits.push((position..position, defaults));
            }
        }
        let root = document.root_element();
        if root.has_tag_name("chartSpace") && !root.children().any(|node| node.has_tag_name("txPr"))
        {
            let position = root
                .children()
                .find(|node| {
                    matches!(
                        node.tag_name().name(),
                        "externalData" | "printSettings" | "userShapes" | "extLst"
                    )
                })
                .map(|node| node.range().start)
                .or_else(|| source.rfind("</c:chartSpace>"))
                .ok_or("chart root closing tag missing")?;
            let size = self.scaled(700);
            edits.push((position..position, format!(r#"<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="{size}"/></a:pPr></a:p></c:txPr>"#)));
        }
        let normalized = apply(source, edits);
        let document = Document::parse(&normalized)?;
        let Some(tree) = document
            .descendants()
            .find(|node| node.has_tag_name("spTree"))
        else {
            return Ok(normalized);
        };
        let mut maximum_id = 0_u32;
        for node in tree.descendants().filter(|node| node.has_tag_name("cNvPr")) {
            let id = node
                .attribute("id")
                .ok_or("shape ID missing")?
                .parse::<u32>()?;
            maximum_id = maximum_id.max(id);
        }
        let content: String = tree
            .children()
            .filter(|node| {
                node.is_element() && !matches!(node.tag_name().name(), "nvGrpSpPr" | "grpSpPr")
            })
            .map(|node| &normalized[node.range()])
            .collect();
        let width = self.scaled(i64::from(self.width));
        let height = self.scaled(i64::from(self.height));
        let [left, top, target_width, target_height] = self.viewport;
        let x = i64::from(left) + (i64::from(target_width) - width) / 2;
        let y = i64::from(top) + (i64::from(target_height) - height) / 2;
        let replacement = format!(
            r#"<p:spTree><p:nvGrpSpPr><p:cNvPr id="{}" name="Showcase canvas"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:grpSp><p:nvGrpSpPr><p:cNvPr id="{}" name="Source slide, centered"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{width}" cy="{height}"/><a:chOff x="0" y="0"/><a:chExt cx="{width}" cy="{height}"/></a:xfrm></p:grpSpPr>
{content}</p:grpSp></p:spTree>"#,
            maximum_id + 1,
            maximum_id + 2
        );
        Ok(apply(&normalized, vec![(tree.range(), replacement)]))
    }
}

fn apply(source: &str, mut edits: Vec<(Range<usize>, String)>) -> String {
    edits.sort_by_key(|(range, _)| std::cmp::Reverse(range.start));
    let mut output = source.to_owned();
    for (range, replacement) in edits {
        output.replace_range(range, &replacement);
    }
    output
}

pub fn layout_ids(source: &str, next_id: &mut u32) -> TestResult<String> {
    let document = Document::parse(source)?;
    let mut edits = Vec::new();
    for node in document
        .descendants()
        .filter(|node| node.has_tag_name("sldLayoutId"))
    {
        let attribute = node.attribute_node("id").ok_or("layout ID missing")?;
        edits.push((attribute.range_value(), next_id.to_string()));
        *next_id += 1;
    }
    Ok(apply(source, edits))
}

pub fn index(chapters: &[(String, String)]) -> String {
    let mut paragraphs = String::from(
        r#"<a:p><a:pPr><a:spcAft><a:spcPts val="1600"/></a:spcAft></a:pPr><a:r><a:rPr sz="3600" b="1"/><a:t>PPTX 功能对照</a:t></a:r></a:p>
<a:p><a:pPr><a:spcAft><a:spcPts val="2400"/></a:spcAft></a:pPr><a:r><a:rPr sz="1800"/><a:t>7 组主题，15 页样例。按展示条件核对预期效果，细节可放大查看。</a:t></a:r></a:p>"#,
    );
    for (title, pages) in chapters {
        paragraphs.push_str(&format!(r#"<a:p><a:pPr><a:spcAft><a:spcPts val="1400"/></a:spcAft></a:pPr><a:r><a:rPr sz="2200"/><a:t>{pages}    {title}</a:t></a:r></a:p>"#));
    }
    format!(
        r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld name="Contents"><p:bg><p:bgPr><a:solidFill><a:srgbClr val="F4F7FA"/></a:solidFill></p:bgPr></p:bg><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Contents"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="609600" y="508000"/><a:ext cx="10972800" cy="5842000"/></a:xfrm><a:noFill/></p:spPr>
<p:txBody><a:bodyPr lIns="0" rIns="0" tIns="0" bIns="0"/><a:lstStyle/>{paragraphs}</p:txBody></p:sp>
</p:spTree></p:cSld></p:sld>"#
    )
}
