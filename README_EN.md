# LinuxHub

English | [简体中文](README.md)

LinuxHub is an offline Ubuntu desktop environment for Android ARM64 devices, currently based on Ubuntu 24.04, XFCE, Termux:X11, and PulseAudio.

## Current Status

- Display name: `LinuxHub`
- applicationId: `com.linuxhub.app`
- versionName: `1.1.902`
- versionCode: `70`
- Build type: debug only
- Release signing: not configured
- Clean-device initialization: final verification still pending

This branch is a rebuild of 1.1.9, not another patch layer on top of the old installer.

## Architecture Direction

- Kotlin/Java handle the Android UI and integration.
- X11, PRoot, and system bridges keep their C/C++ native runtime.
- The stable C ABI layers are not being rewritten merely to claim a single-language codebase. Rust remains an option for future components, not a reason to rewrite the current X11/PRoot stack.
- Offline packages are declared in one place: `tools/offline/package-roots.txt`; versions and hashes are locked in `tools/offline/offline-bootstrap.lock.json`.
- Initialization performs one archive extraction, one `dpkg --unpack`, one `dpkg --configure -a`, and a strict final `dpkg -C` audit.
- The installer does not use online `apt-get update`, `apt-get -f install`, `|| true`, or staged repair scripts to hide failures.
- The Turnip/Zink acceleration path is intentionally unchanged in this rebuild.

## Default System

The default install contains the base system, XFCE, X11, audio, input method, Bluetooth, network management, a file manager, Firefox, Spark Store, and required runtime libraries.

Python, Node.js, GCC, CMake, Java, Rust, Go, AI environments, and Jupyter are not installed by default. They are explicit optional components, not part of the base image.

First-run desktop defaults are written only once:

- Resolution: `1280x800`
- Aspect ratio: 16:10
- Stretch: disabled
- Automatic resolution adjustment: disabled
- Wallpaper: green Ubuntu wallpaper
- GTK theme: WhiteSur-Dark
- Icon theme: Tela Circle

Later user changes to resolution, wallpaper, theme, panel, or icons are not overwritten on every launch.

## Build

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME='/Users/l/Library/Android/sdk'
sh ./gradlew :app:testDebugUnitTest --no-daemon
sh ./gradlew :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Offline Assets

Large assets are not stored in Git:

- `app/src/main/assets/offline/bootstrap-arm64.tar`
- `app/src/main/assets/offline/vendor-apps-arm64.tar`
- `app/src/main/assets/rootfs/ubuntu-base-24.04.4-base-arm64.tar`

See [OFFLINE_ASSETS.md](OFFLINE_ASSETS.md) for generation and packaging details.

## Installation and Limits

- ARM64 only.
- Reserve at least 5 GB of free storage.
- Keep the app in the foreground during initialization.
- The first-run target is 5–6 minutes; final timing depends on real-device testing.
- The current debug APK is not a release artifact. A release keystore and signing configuration must be added before publishing.
- Data from the old applicationId is not migrated automatically.

## Verification Priorities

- Offline initialization from a clean state.
- Empty `dpkg -C` audit for all 583 bootstrap packages.
- Firefox `ldd` reports no missing libraries.
- XFCE, X11, audio, Bluetooth, networking, and input method behavior.
- 16:10 output without stretching and correct first-run defaults.
- Turnip/Zink normal path and software fallback.
- Shizuku authorization and X11 service binding after the package rename.
- Initialization time, rootfs size, and peak memory usage.

## License

Verify the licenses and redistribution terms of the source, themes, icons, drivers, Firefox, Spark Store, and all bundled software before publishing.
