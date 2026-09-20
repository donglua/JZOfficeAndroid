use super::{docx, package::Parts};

pub fn parts() -> Parts {
    let mut parts = docx::parts();
    let mut paragraphs = section(
        "05 行距对照",
        "以下各例均为 11 pt、两行文字。比较同一段内两行的距离，段落之间的留白保持一致。",
    );
    for (label, expected, properties) in [
        (
            "单倍行距",
            "1.0 倍，作为比较基准。",
            r#"<w:spacing w:line="240" w:lineRule="auto"/>"#,
        ),
        (
            "1.5 倍行距",
            "两行间距大于单倍、小于双倍。",
            r#"<w:spacing w:line="360" w:lineRule="auto"/>"#,
        ),
        (
            "双倍行距",
            "2.0 倍，三种倍数中间距最大。",
            r#"<w:spacing w:line="480" w:lineRule="auto"/>"#,
        ),
        (
            "固定 20 pt 行距",
            "固定值，行距不随字号变化。",
            r#"<w:spacing w:line="400" w:lineRule="exact"/>"#,
        ),
        (
            "最小 18 pt 行距",
            "以 18 pt 为下限，字形较高时可撑开。",
            r#"<w:spacing w:line="360" w:lineRule="atLeast"/>"#,
        ),
    ] {
        paragraphs.push_str(&case(
            label,
            properties,
            &format!("{expected}\n第二行：中文与 English 混排，观察两行之间的距离。"),
        ));
    }
    paragraphs.push_str(&section(
        "06 缩进与对齐",
        "各例仅用两行显示差异。前两例左缩进 24 pt、右缩进 12 pt；第三例比较两行右边缘。",
    ));
    for (label, properties, text) in [
        (
            "首行缩进",
            r#"<w:ind w:left="480" w:right="240" w:firstLine="400"/>"#,
            "首行再向右缩进 20 pt。\n第二行回到左缩进位置，比首行靠左。",
        ),
        (
            "悬挂缩进",
            r#"<w:ind w:left="480" w:right="240" w:hanging="320"/>"#,
            "首行向左悬挂 16 pt。\n第二行回到左缩进位置，比首行靠右。",
        ),
        (
            "右对齐",
            r#"<w:jc w:val="right"/>"#,
            "两行文字长短不同，右边缘应齐平。\n较短的第二行。",
        ),
    ] {
        paragraphs.push_str(&case(label, properties, text));
    }
    let title = section(
        "DOCX 综合样例",
        "按章节观察文字、图片、表格和段落排版。每项均标明设置与预期结果。",
    );
    let styles = section(
        "01 文字样式",
        "下方居中样例：蓝色文字继承 18 pt 粗体；红色文字改为 14 pt 斜体、下划线，并关闭粗体。",
    );
    let breaks = section(
        "02 制表符与显式换行",
        "同一段中包含普通字、Italic 斜体、一个 Tab 和一次显式换行；最后一句应另起一行。",
    );
    let image = section(
        "03 嵌入图片",
        "下方青绿色块是 PNG 图片，显示尺寸为 72 × 36 pt，宽高比为 2:1。",
    );
    let table = section(
        "04 表格",
        "两行两列，列宽分别为 144 pt 与 216 pt；右列更宽，中文与 English 内容应完整显示。",
    );
    for (path, bytes) in &mut parts {
        if *path == "word/document.xml" {
            *bytes = docx::DOCUMENT
                .replace("<w:body>", &format!("<w:body>{title}{styles}"))
                .replace("&#x4E2D;&#x6587; Office", "蓝色粗体 18 pt")
                .replace(" English regular", " 红色斜体 14 pt")
                .replace(
                    "<w:p><w:r><w:t>Plain paragraph with ",
                    &format!("{breaks}<w:p><w:r><w:t>普通文字 + "),
                )
                .replace("<w:t>italic</w:t>", "<w:t>Italic</w:t>")
                .replace("<w:t>tab</w:t>", "<w:t>Tab 后的文字</w:t>")
                .replace("<w:t>next line</w:t>", "<w:t>显式换行后的第二行。</w:t>")
                .replace(
                    "<w:p><w:r><w:drawing>",
                    &format!("{image}<w:p><w:r><w:drawing>"),
                )
                .replace("<w:tbl>", &format!("{table}<w:tbl>"))
                .replace("<w:t>Language</w:t>", "<w:t>左列 144 pt</w:t>")
                .replace("<w:t>Greeting</w:t>", "<w:t>右列 216 pt</w:t>")
                .replace("Hello &#x4F60;&#x597D;", "English 混排")
                .replace("<w:sectPr>", &format!("{paragraphs}<w:sectPr>"))
                .into_bytes();
        }
    }
    parts
}

fn section(title: &str, description: &str) -> String {
    format!(
        r#"<w:p><w:pPr><w:pStyle w:val="BaseTitle"/></w:pPr><w:r><w:t>{title}</w:t></w:r></w:p><w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:t>{description}</w:t></w:r></w:p>"#
    )
}

fn case(label: &str, properties: &str, text: &str) -> String {
    let text = text.replace('\n', "</w:t><w:br/><w:t>");
    format!(
        r#"<w:p><w:pPr>{properties}</w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>{label}：</w:t></w:r><w:r><w:t>{text}</w:t></w:r></w:p>"#
    )
}
