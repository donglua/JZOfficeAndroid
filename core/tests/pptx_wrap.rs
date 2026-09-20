pub mod support;

use jz_office_core::{model::ElementType, parse_reader};
use std::io::Cursor;
use support::{
    checks,
    package::{archive, replace, TestResult},
    pptx,
};

#[test]
fn text_wrap_inherits_and_direct_square_restores_wrapping() -> TestResult {
    for (master, layout, direct, expected) in [
        ("", "", "", true),
        ("none", "", "", false),
        ("square", "none", "", false),
        ("none", "", "square", true),
        ("", "square", "none", false),
    ] {
        let mut parts = pptx::parts();
        for ((path, root), wrap) in [
            ("ppt/slideMasters/slideMaster1.xml", "sldMaster"),
            ("ppt/slideLayouts/slideLayout1.xml", "sldLayout"),
            ("ppt/slides/slide2.xml", "sld"),
        ]
        .into_iter()
        .zip([master, layout, direct])
        {
            let wrap = if wrap.is_empty() {
                String::new()
            } else {
                format!(r#"wrap="{wrap}""#)
            };
            replace(
                &mut parts,
                path,
                &format!(
                    r#"<p:{root} xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="2667000" cy="923290"/></a:xfrm></p:spPr><p:txBody><a:bodyPr {wrap} lIns="0" rIns="0" tIns="0" bIns="0"><a:spAutoFit/></a:bodyPr><a:p><a:r><a:rPr sz="6000"/><a:t>PART 0</a:t></a:r><a:r><a:rPr sz="6000"/><a:t>5</a:t></a:r><a:br/><a:r><a:t>Next line</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:{root}>"#
                ),
            );
        }
        let doc = parse_reader(Cursor::new(archive(&parts)?))?;
        let text = checks::element(&doc.pages[0].elements, ElementType::TEXT)?;
        let json = serde_json::to_value(text)?;
        assert_eq!(json["textWrap"], expected);
        assert_eq!(checks::text(&text.paragraphs), "PART 05\nNext line");
        checks::bounds(text, [0.0, 0.0, 210.0, 72.7]);
        assert_eq!(text.paragraphs[0].runs[0].size, 60.0);
        assert!(!doc
            .warnings
            .iter()
            .any(|w| w == "PPTX text uses one wrapped column."));
    }
    Ok(())
}
