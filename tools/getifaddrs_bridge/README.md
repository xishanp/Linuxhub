# LinuxHub getifaddrs bridge

This is the LinuxHub-owned replacement for the container `getifaddrs()` bridge.
It fixes the two problems in the old implementation:

- IPv6 `sockaddr_in6` records are no longer truncated to `sizeof(struct sockaddr)`.
- Every request/response is framed as `TIF1 + uint32 length`, with exact reads
  and writes. EOF, a short read, or a malformed frame is never interpreted as
  the old one-byte `'S'` signal.

The client falls back to the real libc `getifaddrs()` if the helper is not
available, so a missing bridge cannot terminate every preloaded application.
The server uses a one-request-per-connection protocol and a private Unix socket.

## Build

The **default build path is the Rust implementation** in
`rust/getifaddrs-bridge`. It produces both the Android ARM64 helper and the
Ubuntu 24.04 glibc `LD_PRELOAD` client. The old C files remain as a reference
and fallback; they are not allowed to silently overwrite the Rust APK assets.

```sh
CARGO_ZIGBUILD_ZIG_COMMAND=/path/to/zig \
  tools/getifaddrs_bridge/build.sh
```

Set `LEGACY_C=1` only when deliberately rebuilding the old C implementation:

```sh
LEGACY_C=1 AARCH64_CC=/path/to/aarch64-linux-gnu-gcc \
  tools/getifaddrs_bridge/build.sh
```

The Rust build requires `cargo-ndk`, `cargo-zigbuild`, and an ARM64 glibc target.
The client is linked against glibc 2.39 to match Ubuntu 24.04.

### Legacy C build

Server (Android/arm64):

```sh
NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang \
  -O2 -fPIE -pie -Wl,-z,relro,-z,now \
  getifaddrs_bridge_server.c -o libexec_getifaddrs_bridge_server.so
```

Client (Ubuntu 24.04 arm64 container):

```sh
gcc -O2 -fPIC -shared -Wl,-soname,libgetifaddrs_bridge.so \
  getifaddrs_bridge_client_lib.c -o libgetifaddrs_bridge.so -ldl
```

The app assets use these paths:

- `app/src/main/assets/runtime/arm64-v8a/libgetifaddrs_bridge.so`
- `app/src/main/jniLibs/arm64-v8a/libexec_getifaddrs_bridge_server.so`

The client is copied from the APK into the container by `installRuntime()` on
every terminal/desktop start, so an APK update cannot leave an old client in the
rootfs.
