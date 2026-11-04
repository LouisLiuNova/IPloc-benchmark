use std::{env, process::Command};
fn main() {
    println!("cargo:rerun-if-env-changed=CARGO_ENCODED_RUSTFLAGS");
    let compiler = Command::new(env::var("RUSTC").unwrap())
        .arg("--version")
        .output()
        .unwrap();
    let flags = env::var("CARGO_ENCODED_RUSTFLAGS").unwrap_or_default();
    let flags_hex: String = flags.bytes().map(|b| format!("{b:02x}")).collect();
    let info = format!(
        "{{\"rustc\":{:?},\"target\":{:?},\"profile\":{:?},\"encodedRustflagsHex\":{:?}}}",
        String::from_utf8(compiler.stdout).unwrap().trim(),
        env::var("TARGET").unwrap(),
        env::var("PROFILE").unwrap(),
        flags_hex
    );
    println!("cargo:rustc-env=IPLOC_BUILD_INFO={info}");
}
