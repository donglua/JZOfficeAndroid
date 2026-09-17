use super::pptx_shapes::GROUP;

pub const COLOR_MAP: &str = r#"<p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4"
accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" tx1="dk1" tx2="dk2" hlink="hlink" folHlink="folHlink"/>"#;

pub fn master() -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld name="Fixture master"><p:spTree>{GROUP}</p:spTree></p:cSld>{COLOR_MAP}
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rLayout"/></p:sldLayoutIdLst>
<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle><a:lvl1pPr>
<a:defRPr sz="1800"><a:solidFill><a:srgbClr val="202124"/></a:solidFill></a:defRPr>
</a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"#
    )
}

pub fn layout() -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank" preserve="1">
<p:cSld name="Blank"><p:spTree>{GROUP}</p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"#
    )
}

pub fn theme() -> String {
    let mut text = String::from(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Fixture theme">
<a:themeElements><a:clrScheme name="Fixture colors">"#,
    );
    for (name, color) in [
        ("dk1", "202124"),
        ("lt1", "FFFFFF"),
        ("dk2", "112233"),
        ("lt2", "F4F7FA"),
        ("accent1", "2A9D8F"),
        ("accent2", "E9C46A"),
        ("accent3", "E76F51"),
        ("accent4", "457B9D"),
        ("accent5", "8F5D9F"),
        ("accent6", "6A994E"),
        ("hlink", "0563C1"),
        ("folHlink", "954F72"),
    ] {
        text.push_str(&format!(
            r#"<a:{name}><a:srgbClr val="{color}"/></a:{name}>"#
        ));
    }
    text.push_str(r#"</a:clrScheme><a:fontScheme name="Fixture fonts">"#);
    for font in ["majorFont", "minorFont"] {
        text.push_str(&format!(
            r#"<a:{font}><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/>
<a:font script="Hans" typeface="Microsoft YaHei"/></a:{font}>"#
        ));
    }
    text.push_str(r#"</a:fontScheme><a:fmtScheme name="Fixture format"><a:fillStyleLst>"#);
    for _ in 0..3 {
        text.push_str(r#"<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"#);
    }
    text.push_str("</a:fillStyleLst><a:lnStyleLst>");
    for width in [12700, 25400, 38100] {
        text.push_str(&format!(r#"<a:ln w="{width}" cap="flat" cmpd="sng" algn="ctr">
<a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/><a:miter lim="800000"/>
<a:headEnd type="none" w="med" len="med"/><a:tailEnd type="none" w="med" len="med"/></a:ln>"#));
    }
    text.push_str("</a:lnStyleLst><a:effectStyleLst>");
    for _ in 0..3 {
        text.push_str("<a:effectStyle><a:effectLst/></a:effectStyle>");
    }
    text.push_str("</a:effectStyleLst><a:bgFillStyleLst>");
    for _ in 0..3 {
        text.push_str(r#"<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"#);
    }
    text.push_str("</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>");
    text
}
