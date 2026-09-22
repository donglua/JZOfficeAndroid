use super::unsigned;
use crate::model::Document;
use crate::xml::Node;
use crate::{Error, Result};

pub(super) struct Colors {
    theme: [u32; 12],
    has_theme: bool,
    indexed: [u32; 64],
}

impl Colors {
    pub(super) fn new(theme: Option<&Node>, palette: Option<&Node>) -> Result<Self> {
        let mut result = Self {
            theme: [
                0xffffffff, 0xff000000, 0xffeeece1, 0xff1f497d, 0xff4f81bd, 0xffc0504d, 0xff9bbb59,
                0xff8064a2, 0xff4bacc6, 0xfff79646, 0xff0000ff, 0xff800080,
            ],
            has_theme: theme.is_some(),
            indexed: INDEXED,
        };
        if let Some(palette) = palette {
            for (value, node) in result.indexed.iter_mut().zip(palette.named("rgbColor")) {
                *value = rgb(node.attr("rgb"))?;
            }
        }
        if let Some(scheme) = theme.and_then(|theme| theme.descendant("clrScheme")) {
            for (index, name) in [
                "lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5",
                "accent6", "hlink", "folHlink",
            ]
            .iter()
            .enumerate()
            {
                if let Some(color) = scheme.child(name).and_then(|n| n.children.first()) {
                    let value = match color.name.as_str() {
                        "srgbClr" => color.attr("val"),
                        "sysClr" => color.attr("lastClr"),
                        _ => continue,
                    };
                    result.theme[index] = rgb(value)?;
                }
            }
        }
        Ok(result)
    }

    pub(super) fn color(
        &self,
        node: Option<&Node>,
        fallback: u32,
        document: &mut Document,
    ) -> Result<u32> {
        let Some(node) = node else {
            return Ok(fallback);
        };
        let value = if !node.attr("rgb").is_empty() {
            rgb(node.attr("rgb"))?
        } else if !node.attr("theme").is_empty() {
            let index = usize::try_from(unsigned(node.attr("theme"))?)
                .map_err(|_| Error::Invalid("Invalid XLSX theme color"))?;
            if !self.has_theme {
                document.warn(
                    "Theme colors use the default Office palette because the theme is missing",
                );
            }
            *self
                .theme
                .get(index)
                .ok_or(Error::Invalid("Invalid XLSX theme color"))?
        } else if !node.attr("indexed").is_empty() {
            let index = unsigned(node.attr("indexed"))?;
            match index {
                0..=63 => {
                    let index = usize::try_from(index)
                        .map_err(|_| Error::Invalid("Invalid XLSX indexed color"))?;
                    0xff000000 | self.indexed[index]
                }
                64 | 65 => fallback,
                _ => return Err(Error::Invalid("Invalid XLSX indexed color")),
            }
        } else {
            fallback
        };
        let tint = node.attr("tint");
        if !tint.is_empty() {
            let tint = tint
                .parse::<f64>()
                .map_err(|_| Error::Invalid("Invalid XLSX color tint"))?;
            if !tint.is_finite() || !(-1.0..=1.0).contains(&tint) {
                return Err(Error::Invalid("Invalid XLSX color tint"));
            }
            if tint != 0.0 {
                return Ok(tinted(value, tint));
            }
        }
        Ok(value)
    }
}

fn tinted(value: u32, tint: f64) -> u32 {
    let mut channels = [(value >> 16) & 255, (value >> 8) & 255, value & 255]
        .map(|channel| f64::from(channel) / 255.0);
    let min = channels.into_iter().fold(1.0, f64::min);
    let max = channels.into_iter().fold(0.0, f64::max);
    let lightness = (min + max) / 2.0;
    let adjusted = if tint < 0.0 {
        lightness * (1.0 + tint)
    } else {
        lightness * (1.0 - tint) + tint
    };
    if max == min {
        channels = [adjusted; 3];
    } else {
        let saturation = (max - min) / (1.0 - (max + min - 1.0).abs());
        let chroma = (1.0 - (2.0 * adjusted - 1.0).abs()) * saturation;
        for channel in &mut channels {
            *channel = (*channel - min) / (max - min) * chroma + adjusted - chroma / 2.0;
        }
    }
    let [red, green, blue] =
        channels.map(|channel| (channel.clamp(0.0, 1.0) * 255.0).round() as u32);
    (value & 0xff000000) | (red << 16) | (green << 8) | blue
}

fn rgb(value: &str) -> Result<u32> {
    if !matches!(value.len(), 6 | 8) || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(Error::Invalid("Invalid XLSX RGB color"));
    }
    let result =
        u32::from_str_radix(value, 16).map_err(|_| Error::Invalid("Invalid XLSX RGB color"))?;
    Ok(0xff000000 | result)
}

const INDEXED: [u32; 64] = [
    0x000000, 0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff, 0x00ffff, 0x000000,
    0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff, 0x00ffff, 0x800000, 0x008000,
    0x000080, 0x808000, 0x800080, 0x008080, 0xc0c0c0, 0x808080, 0x9999ff, 0x993366, 0xffffcc,
    0xccffff, 0x660066, 0xff8080, 0x0066cc, 0xccccff, 0x000080, 0xff00ff, 0xffff00, 0x00ffff,
    0x800080, 0x800000, 0x008080, 0x0000ff, 0x00ccff, 0xccffff, 0xccffcc, 0xffff99, 0x99ccff,
    0xff99cc, 0xcc99ff, 0xffcc99, 0x3366ff, 0x33cccc, 0x99cc00, 0xffcc00, 0xff9900, 0xff6600,
    0x666699, 0x969696, 0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399,
    0x333333,
];
