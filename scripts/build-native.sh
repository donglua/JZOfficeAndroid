#!/usr/bin/env bash
set -euo pipefail

native_output="$1"
shift
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed NDK 28 or newer}"
case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *) echo 'This build script supports macOS and Linux.' >&2; exit 1 ;;
esac
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android23-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$toolchain/x86_64-linux-android23-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$toolchain/armv7a-linux-androideabi23-clang"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) rust_target=aarch64-linux-android ;;
        x86_64) rust_target=x86_64-linux-android ;;
        armeabi-v7a) rust_target=armv7-linux-androideabi ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    cargo build --locked --release -p jz-office-android --target "$rust_target"
    mkdir -p "$native_output/$abi"
    cp "target/$rust_target/release/libjz_office.so" "$native_output/$abi/"
done
