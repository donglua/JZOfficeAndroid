use super::package::{content_types, relationships, xml, Parts, PNG};
use super::{pptx_shapes as shapes, pptx_theme as theme};

pub const PRESENTATION: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rMaster"/></p:sldMasterIdLst>
<p:sldIdLst><p:sldId id="257" r:id="rSecond"/><p:sldId id="256" r:id="rFirst"/></p:sldIdLst>
<p:sldSz cx="9144000" cy="5143500" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/>
<p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US" sz="1200"/></a:defPPr></p:defaultTextStyle>
</p:presentation>"#;

pub fn parts() -> Parts {
    let first = format!(
        "{}{}{}{}",
        shapes::title("First &#x4E2D;&#x6587;", "112233"),
        shapes::GEOMETRY,
        shapes::IMAGE,
        shapes::table()
    );
    vec![
        xml(
            "[Content_Types].xml",
            &content_types(&[
                ("ppt/presentation.xml", "presentationml.presentation.main"),
                ("ppt/slides/slide1.xml", "presentationml.slide"),
                ("ppt/slides/slide2.xml", "presentationml.slide"),
                (
                    "ppt/slideLayouts/slideLayout1.xml",
                    "presentationml.slideLayout",
                ),
                (
                    "ppt/slideMasters/slideMaster1.xml",
                    "presentationml.slideMaster",
                ),
                ("ppt/theme/theme1.xml", "theme"),
            ]),
        ),
        xml(
            "_rels/.rels",
            &relationships(&[("rOffice", "officeDocument", "ppt/presentation.xml")]),
        ),
        xml("ppt/presentation.xml", PRESENTATION),
        xml(
            "ppt/_rels/presentation.xml.rels",
            &relationships(&[
                ("rFirst", "slide", "slides/slide1.xml"),
                ("rSecond", "slide", "slides/slide2.xml"),
                ("rMaster", "slideMaster", "slideMasters/slideMaster1.xml"),
            ]),
        ),
        xml(
            "ppt/slides/slide1.xml",
            &slide("112233", &shapes::title("Second English", "FFFFFF")),
        ),
        xml("ppt/slides/slide2.xml", &slide("F4F7FA", &first)),
        xml(
            "ppt/slides/_rels/slide1.xml.rels",
            &relationships(&[("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml")]),
        ),
        xml(
            "ppt/slides/_rels/slide2.xml.rels",
            &relationships(&[
                ("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                ("rImage", "image", "../media/../media/pixel.png"),
            ]),
        ),
        xml("ppt/slideLayouts/slideLayout1.xml", &theme::layout()),
        xml(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            &relationships(&[("rMaster", "slideMaster", "../slideMasters/slideMaster1.xml")]),
        ),
        xml("ppt/slideMasters/slideMaster1.xml", &theme::master()),
        xml(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            &relationships(&[
                ("rLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                ("rTheme", "theme", "../theme/theme1.xml"),
            ]),
        ),
        xml("ppt/theme/theme1.xml", &theme::theme()),
        ("ppt/media/pixel.png", PNG.to_vec()),
    ]
}

fn slide(background: &str, content: &str) -> String {
    let group = shapes::GROUP;
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="{background}"/></a:solidFill></p:bgPr></p:bg>
<p:spTree>{group}{content}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"#
    )
}
