#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SOURCE="$ROOT/tools/proot/upstream"
INCLUDE="$ROOT/tools/proot/include"
VERSION_HELPER="$ROOT/tools/proot/git-version.sh"
NDK_VERSION=${NDK_VERSION:-29.0.14206865}
ANDROID_API=${ANDROID_API:-28}

SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)
fi
if [[ -z "$SDK_ROOT" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
    exit 1
fi

NDK_ROOT=${ANDROID_NDK_HOME:-}
if [[ -z "$NDK_ROOT" ]]; then
    NDK_ROOT="$SDK_ROOT/ndk/$NDK_VERSION"
fi
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo "unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
LLVM_BIN="$TOOLCHAIN/bin"
CC="$LLVM_BIN/aarch64-linux-android${ANDROID_API}-clang"

for tool in "$CC" "$LLVM_BIN/llvm-strip" "$LLVM_BIN/llvm-objcopy" "$LLVM_BIN/llvm-objdump"; do
    if [[ ! -x "$tool" ]]; then
        echo "missing Android build tool: $tool" >&2
        exit 1
    fi
done

for dependency in \
    "$ROOT/app/src/main/assets/runtime/arm64-v8a/lib/libtalloc.so.2" \
    "$ROOT/app/src/main/assets/runtime/arm64-v8a/lib/libandroid-shmem.so"; do
    if [[ ! -f "$dependency" ]]; then
        echo "missing PRoot link dependency: $dependency" >&2
        exit 1
    fi
done

BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/linuxhub-proot-build.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' EXIT
mkdir -p "$BUILD_DIR/source" "$BUILD_DIR/lib" "$BUILD_DIR/bin"
cp -R "$SOURCE/." "$BUILD_DIR/source/"
cp "$ROOT/app/src/main/assets/runtime/arm64-v8a/lib/libtalloc.so.2" "$BUILD_DIR/lib/libtalloc.so"
cp "$ROOT/app/src/main/assets/runtime/arm64-v8a/lib/libandroid-shmem.so" "$BUILD_DIR/lib/libandroid-shmem.so"
ln -s "$LLVM_BIN/llvm-readelf" "$BUILD_DIR/bin/readelf"

JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
make -C "$BUILD_DIR/source/src" -f GNUmakefile clean >/dev/null 2>&1 || true
PATH="$BUILD_DIR/bin:$PATH" make -C "$BUILD_DIR/source/src" -f GNUmakefile -j"$JOBS" \
    CC="$CC" \
    GIT="$VERSION_HELPER" \
    CFLAGS="-Wall -Wextra -O2 -fPIC -I$INCLUDE -DWITH_LIBANDROID_SHMEM -DPROOT_UNBUNDLE_LOADER='\"/data/data/com.termux/files/usr/libexec/proot\"'" \
    LDFLAGS="-L$BUILD_DIR/lib -ltalloc -landroid-shmem -Wl,-z,noexecstack" \
    PROOT_UNBUNDLE_LOADER=/data/data/com.termux/files/usr/libexec/proot \
    PROOT_WITH_LIBANDROID_SHMEM=1 \
    proot

PROOT_BUILD="$BUILD_DIR/source/src/proot"
LOADER_BUILD="$BUILD_DIR/source/src/loader/loader"
if [[ ! -s "$PROOT_BUILD" || ! -s "$LOADER_BUILD" ]]; then
    echo "PRoot build did not produce both binaries" >&2
    exit 1
fi

"$LLVM_BIN/llvm-strip" --strip-all "$PROOT_BUILD" "$LOADER_BUILD"
mkdir -p \
    "$ROOT/app/src/main/jniLibs/arm64-v8a" \
    "$ROOT/app/src/main/assets/runtime/arm64-v8a"
cp "$PROOT_BUILD" "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so"
cp "$LOADER_BUILD" "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
cp "$PROOT_BUILD" "$ROOT/app/src/main/assets/runtime/arm64-v8a/proot"
cp "$LOADER_BUILD" "$ROOT/app/src/main/assets/runtime/arm64-v8a/loader"
chmod 755 \
    "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so" \
    "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" \
    "$ROOT/app/src/main/assets/runtime/arm64-v8a/proot" \
    "$ROOT/app/src/main/assets/runtime/arm64-v8a/loader"

printf 'PRoot %s built with Android API %s\n' "$("$VERSION_HELPER")" "$ANDROID_API"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum \
        "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so" \
        "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
else
    shasum -a 256 \
        "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so" \
        "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
fi
