use super::{package::Parts, xlsx};

pub fn parts() -> Parts {
    let mut parts = xlsx::parts();
    for (name, bytes) in &mut parts {
        let replacement = match *name {
            "xl/worksheets/sheet2.xml" => Some(overview()),
            "xl/worksheets/sheet1.xml" => Some(data()),
            "xl/styles.xml" => Some(styles()),
            _ => None,
        };
        if let Some(text) = replacement {
            *bytes = text.into_bytes();
        }
    }
    parts
}

fn overview() -> String {
    let mut rows = row(1, 42, &text("A1", 1, "格式对照｜B 实际 · C 预期"));
    for (index, condition, value, expected) in [
        (
            2,
            "千分位\n数值 1234.5",
            r#"<c r="B2" s="2"><v>1234.5</v></c>"#,
            "1,234.50\n每三位分组，保留两位小数",
        ),
        (
            3,
            "百分比\n数值 0.125",
            r#"<c r="B3" s="3"><v>0.125</v></c>"#,
            "12.50%\n小数转为百分比",
        ),
        (
            4,
            "日期\n序列值 45292",
            r#"<c r="B4" s="4"><v>45292</v></c>"#,
            "2024-01-01\n按年月日显示",
        ),
        (
            5,
            "有缓存公式\nB2 × 2",
            r#"<c r="B5" s="2"><f>B2*2</f><v>2469</v></c>"#,
            "2,469.00\n显示文件中的缓存结果",
        ),
        (
            6,
            "布尔值\n存储值 1",
            r#"<c r="B6" t="b"><v>1</v></c>"#,
            "TRUE\n布尔类型不显示为数字",
        ),
        (
            7,
            "错误值\n除数为零",
            r#"<c r="B7" t="e"><v>#DIV/0!</v></c>"#,
            "#DIV/0!\n保留原始错误标记",
        ),
        (
            8,
            "时间\n一天的 0.5",
            r#"<c r="B8" s="5"><v>0.5</v></c>"#,
            "12:00:00\n小数转为时分秒",
        ),
        (
            9,
            "无缓存公式\nSUM(B2:B3)",
            r#"<c r="B9"><f>SUM(B2:B3)</f></c>"#,
            "=SUM(B2:B3)\n显示公式，不在本地计算",
        ),
        (
            10,
            "分段文本\n两段拼接",
            r#"<c r="B10" t="inlineStr"><is><r><t>Rich </t></r><r><t>text</t></r></is></c>"#,
            "Rich text\n保留分段之间的空格",
        ),
        (
            11,
            "共享字符串\n多段与注音",
            r#"<c r="B11" t="s"><v>0</v></c>"#,
            "Quarterly Overview\n拼接正文，不显示注音",
        ),
    ] {
        rows.push_str(&row(
            index,
            42,
            &format!(
                "{}{value}{}",
                text(&format!("A{index}"), 7, condition),
                text(&format!("C{index}"), 7, expected),
            ),
        ));
    }
    rows.push_str(&row(
        12,
        30,
        &text("A12", 8, "样式与尺寸｜合并后仍按单元格排版"),
    ));
    rows.push_str(&row(
        13,
        72,
        &format!(
            "{}{}",
            text("A13", 7, "B13:C13 合并\n行高 72 pt\n居中与换行"),
            text("B13", 1, "粗斜体与下划线\n白字 · 绿底 · 边框"),
        ),
    ));
    rows.push_str(&row(
        14,
        38,
        &text(
            "A14",
            7,
            "预期：绿区跨两列，文字分两行居中。\n列宽不同，边框连续。",
        ),
    ));
    rows.push_str(&row(
        15,
        30,
        &text("A15", 8, "滚动坐标尺｜向下到第 120 行，向右到 L 列"),
    ));
    rows.push_str(&row(
        16,
        42,
        &text(
            "A16",
            7,
            "首行及每 10 行显示横向刻度，其余只显示行号。\n向右、向下滚动，终点为 L120。",
        ),
    ));
    for index in 17..=120 {
        let mut cells = text(&format!("A{index}"), 0, &format!("A{index}"));
        if index == 17 || index % 10 == 0 {
            for column in b'B'..=b'L' {
                let coordinate = format!("{}{index}", char::from(column));
                cells.push_str(&text(&coordinate, 0, &coordinate));
            }
        }
        rows.push_str(&row(index, 18, &cells));
    }
    worksheet(
        &rows,
        &[
            "A1:C1", "A12:C12", "B13:C13", "A14:C14", "A15:C15", "A16:C16",
        ],
        true,
    )
}

fn data() -> String {
    let rows = [
        row(1, 42, &text("A1", 1, "数值与文本公式")),
        row(2, 36, r#"<c r="A2"><v>42</v></c><c r="B2" t="str"><f>"ready"</f><v>ready</v></c>"#),
        row(3, 54, &format!("{}{}{}", text("A3", 7, "整数值\n预期：42"), text("B3", 7, "文本公式缓存\n预期：ready"), text("C3", 7, "B2 只显示文件缓存\n不重新计算公式"))),
        row(4, 30, &text("A4", 8, "工作表切换｜名称与顺序")),
        row(5, 54, &text("A5", 7, "预期：Overview 在前，Data 在后。点击底部标签切换，顶部页码应同步为 1 / 2 或 2 / 2。")),
    ].concat();
    worksheet(&rows, &["A1:C1", "A4:C4", "A5:C5"], false)
}

fn worksheet(rows: &str, merges: &[&str], scroll: bool) -> String {
    let merges: String = merges
        .iter()
        .map(|range| format!(r#"<mergeCell ref="{range}"/>"#))
        .collect();
    let extra_columns = if scroll {
        r#"<col min="4" max="12" width="16"/>"#
    } else {
        ""
    };
    xlsx::sheet(&format!(
        r#"<sheetFormatPr defaultRowHeight="18" defaultColWidth="12"/><cols><col min="1" max="1" width="18"/><col min="2" max="2" width="21"/><col min="3" max="3" width="28"/>{extra_columns}</cols><sheetData>{rows}</sheetData><mergeCells>{merges}</mergeCells>"#
    ))
}

fn row(index: u32, height: u32, cells: &str) -> String {
    format!(r#"<row r="{index}" ht="{height}" customHeight="1">{cells}</row>"#)
}

fn text(coordinate: &str, style: u32, value: &str) -> String {
    format!(
        r#"<c r="{coordinate}" s="{style}" t="inlineStr"><is><t xml:space="preserve">{value}</t></is></c>"#
    )
}

fn styles() -> String {
    xlsx::STYLES
        .replace(r#"<fills count="3">"#, r#"<fills count="4">"#)
        .replace("</fills>", r#"<fill><patternFill patternType="solid"><fgColor rgb="FFE4F0F5"/></patternFill></fill></fills>"#)
        .replace(r#"<cellXfs count="7">"#, r#"<cellXfs count="9">"#)
        .replace("</cellXfs>", r#"<xf numFmtId="0" fontId="0" fillId="0" borderId="0"><alignment horizontal="left" vertical="bottom" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="0"><alignment horizontal="left" vertical="center" wrapText="1"/></xf></cellXfs>"#)
}
