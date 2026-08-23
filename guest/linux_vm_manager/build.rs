use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    rsbinder_aidl::Builder::new()
        .source(PathBuf::from("../../android/virtualizationservice/aidl_for_non_microdroid/"))
        .source(PathBuf::from("../../libs/debian_service/aidl/"))
        .output(PathBuf::from("aidl.rs"))
        .generate()?;
    Ok(())
}

