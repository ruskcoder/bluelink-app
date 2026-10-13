use std::{env, path::PathBuf};

fn main() {
    assert!(
        env::var_os("CARGO_FEATURE_DYNAMIC_LINKAGE").is_some(),
        "the vendored Unicorn bindings support dynamic linkage only",
    );

    let target = env::var("TARGET").expect("Cargo did not set TARGET");
    let target_var = format!("UNICORN_LIB_DIR_{}", target.replace('-', "_"));
    println!("cargo:rerun-if-env-changed={target_var}");
    println!("cargo:rerun-if-env-changed=UNICORN_LIB_DIR");

    let lib_dir = env::var_os(&target_var)
        .or_else(|| env::var_os("UNICORN_LIB_DIR"))
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            panic!("set {target_var} to the directory containing the Unicorn library")
        });
    let lib_dir = lib_dir
        .canonicalize()
        .unwrap_or_else(|error| panic!("invalid Unicorn library directory {lib_dir:?}: {error}"));
    // Android links libunicorn.so; a host build (which is how the emulator gets tested off-device)
    // links whatever its own platform names the library.
    let candidates: &[&str] = if target.contains("windows") {
        &["unicorn.lib", "unicorn.dll.lib", "libunicorn.dll.a"]
    } else if target.contains("apple") {
        &["libunicorn.dylib", "libunicorn.so"]
    } else {
        &["libunicorn.so"]
    };
    assert!(
        candidates.iter().any(|name| lib_dir.join(name).is_file()),
        "{} contains none of {candidates:?}",
        lib_dir.display(),
    );

    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-lib=dylib=unicorn");
}
