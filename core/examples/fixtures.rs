#[path = "../tests/support/mod.rs"]
pub mod support;

use std::{env, fs, path::PathBuf};
use support::package::{archive, TestResult};

fn main() -> TestResult {
    let destination = env::args_os()
        .nth(1)
        .map_or_else(|| PathBuf::from("samples"), PathBuf::from);
    fs::create_dir_all(&destination)?;
    for (name, parts) in [
        ("sample.docx", support::docx::parts()),
        ("sample.pptx", support::pptx::parts()),
    ] {
        fs::write(destination.join(name), archive(&parts)?)?;
    }
    Ok(())
}
