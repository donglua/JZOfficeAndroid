#[path = "../tests/support/mod.rs"]
pub mod support;

use std::{env, fs, path::PathBuf};
use support::package::{archive, TestResult};

fn main() -> TestResult {
    let mut args = env::args_os().skip(1);
    let first = args.next();
    if first.as_deref() == Some(std::ffi::OsStr::new("--help")) {
        println!("Usage: fixtures [OUTPUT]\n       fixtures --tests [OUTPUT]\nGenerate the three demo documents, or the focused Android test fixtures.");
        return Ok(());
    }
    let tests = first.as_deref() == Some(std::ffi::OsStr::new("--tests"));
    let output = if tests { args.next() } else { first };
    if args.next().is_some()
        || output
            .as_ref()
            .is_some_and(|value| value.to_string_lossy().starts_with('-'))
    {
        return Err("Usage: fixtures [--tests] [OUTPUT]".into());
    }
    let destination = output.map_or_else(
        || {
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(if tests {
                "../viewer/build/generated/test-fixtures"
            } else {
                "../demo/src/main/assets/samples"
            })
        },
        PathBuf::from,
    );
    fs::create_dir_all(&destination)?;
    let documents = if tests {
        test_documents()?
    } else {
        vec![
            ("sample.docx", archive(&support::docx_showcase::parts())?),
            ("sample.pptx", archive(&support::pptx_showcase::parts()?)?),
            ("sample.xlsx", archive(&support::xlsx_showcase::parts())?),
        ]
    };
    for (name, bytes) in documents {
        let path = destination.join(name);
        if fs::read(&path).ok().as_deref() != Some(bytes.as_slice()) {
            fs::write(path, bytes)?;
        }
    }
    Ok(())
}

fn test_documents() -> TestResult<Vec<(&'static str, Vec<u8>)>> {
    [
        ("sample.docx", support::docx::parts()),
        ("sample.pptx", support::pptx::parts()),
        ("pptx-compat.pptx", support::pptx_compat::parts()),
        ("pptx-charts.pptx", support::pptx_charts::parts()),
        ("pptx-colors.pptx", support::pptx_colors::parts()),
        ("pptx-typography.pptx", support::pptx_typography::parts()),
        ("pptx-wrapping.pptx", support::pptx_wrapping::parts()),
        ("pptx-backgrounds.pptx", support::pptx_backgrounds::parts()),
        ("sample.xlsx", support::xlsx::parts()),
    ]
    .into_iter()
    .map(|(name, parts)| Ok((name, archive(&parts)?)))
    .collect()
}
