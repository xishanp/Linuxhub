#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/tools/getifaddrs_bridge"
NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
HOST_TAG=darwin-x86_64
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"

if [[ "${LEGACY_C:-0}" == "1" ]]; then
  echo "Building legacy C bridge (not the default APK path)..." >&2
  "$CC" -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,relro,-z,now \
    "$SRC/getifaddrs_bridge_server.c" \
    -o "$ROOT/app/src/main/jniLibs/arm64-v8a/libexec_getifaddrs_bridge_server.so"

  if [[ -n "${AARCH64_CC:-}" ]]; then
    "$AARCH64_CC" -O2 -Wall -Wextra -Werror -fPIC -shared \
      -Wl,-soname,libgetifaddrs_bridge.so \
      "$SRC/getifaddrs_bridge_client_lib.c" \
      -o "$ROOT/app/src/main/assets/runtime/arm64-v8a/libgetifaddrs_bridge.so" -ldl
  else
    echo "legacy server built; set AARCH64_CC to build the legacy client" >&2
  fi
  exit 0
fi

RUST_DIR="$ROOT/rust/getifaddrs-bridge"
ZIG_BIN=${CARGO_ZIGBUILD_ZIG_COMMAND:-$(command -v zig || true)}
if [[ -z "$ZIG_BIN" ]]; then
  echo "zig is required for the Ubuntu glibc client; set CARGO_ZIGBUILD_ZIG_COMMAND" >&2
  exit 2
fi
ZIG_VERSION=${CARGO_ZIGBUILD_ZIG_VERSION:-0.14.1}

(
  cd "$RUST_DIR"
  cargo fmt --all --check
  cargo clippy --workspace --target aarch64-unknown-linux-gnu --all-targets -- -D warnings

  CARGO_ZIGBUILD_ZIG_COMMAND="$ZIG_BIN" \
  CARGO_ZIGBUILD_ZIG_VERSION="$ZIG_VERSION" \
    cargo zigbuild --release -p linuxhub-getifaddrs-client \
      --target aarch64-unknown-linux-gnu.2.39

  cargo ndk -P 26 -t arm64-v8a build --release \
    -p linuxhub-getifaddrs-server --bin getifaddrs_bridge_server
)

cp "$RUST_DIR/target/aarch64-unknown-linux-gnu/release/libgetifaddrs_bridge.so" \
  "$ROOT/app/src/main/assets/runtime/arm64-v8a/libgetifaddrs_bridge.so"
cp "$RUST_DIR/target/aarch64-linux-android/release/getifaddrs_bridge_server" \
  "$ROOT/app/src/main/jniLibs/arm64-v8a/libexec_getifaddrs_bridge_server.so"
chmod 755 "$ROOT/app/src/main/jniLibs/arm64-v8a/libexec_getifaddrs_bridge_server.so"

file "$ROOT/app/src/main/assets/runtime/arm64-v8a/libgetifaddrs_bridge.so" \
  "$ROOT/app/src/main/jniLibs/arm64-v8a/libexec_getifaddrs_bridge_server.so"
