# Changelog

All notable changes to LinuxHub are documented here.

The format follows Keep a Changelog and release names use the project mythology scheme.

## [2.0.0-myth-prometheus] - 2026-11-22

### Added

- Ubuntu 24.04.4 ARM64 with official GNOME Shell 46 and Mutter 46 X11
- Offline GNOME, Mesa, desktop application, Chinese locale, and user-directory deployment
- Qualcomm Adreno Zink / Turnip and MediaTek Mali VirGL / virpipe application rendering
- USB and LAN browser remote desktop with H.264 MediaCodec and WebCodecs
- Rust session controller and Rust getifaddrs network bridge
- PulseAudio FIFO to Android AudioTrack output bridge
- XDG download directories, Android shared-storage bindings, trusted CA repair, and `.deb` installer
- Content-addressed vendor application migration for APK overwrite upgrades
- Liquid-glass Android control center, desktop diagnostics, and first-frame readiness checks
- Gnome desktop screenshot in the repository introduction

### Changed

- Android package identity is now `com.linuxhub.myth` with version `Myth-Prometheus`
- GNOME Shell remains on llvmpipe while desktop applications use the selected vendor GPU
- Desktop launchers, terminal commands, Chrome, and packaged applications share the same GPU launch path
- APT post-install hooks refresh desktop entries, MIME associations, and launch wrappers
- The terminal overview uses a narrow-screen two-stage fastfetch layout with a text fallback

### Fixed

- Mesa 27 AArch64 CPU feature detection and Qualcomm SVE `SIGILL`
- GNOME first-frame timeout and invisible windows
- Qualcomm GTK4 damaged-region artifacts with `GSK_RENDERER=cairo`
- Vendor GID group-name errors under `su`
- Oplus SurfaceView three-second buffer warnings with a one-frame-per-second idle keepalive
- User activity refresh every 120 seconds to prevent long-task screen sleep
- PRoot memfd read/write handling and Android seccomp-denied syscall recovery
- X11 Present, VBlank, Damage, GPU-copy completion, and stopped-session cleanup

### Boundaries

- Browser hardware video encoding is deferred to the next release
- Production Chrome Wayland integration is deferred to the next release
- AAC / MP4 packaging is deferred to the next release
- The Canvas 2D left-edge artifact under Wayland / Zink remains unresolved

# Changelog

All notable changes to LinuxHub are documented here.

The format follows Keep a Changelog and the project uses semantic versioning where practical.

## [Unreleased]

### Added

- Ubuntu 26.04 ARM64 deployment
- KDE Plasma 6 Wayland desktop
- PRoot-based Linux runtime
- Anland Android display consumer
- KWin Anland display integration
- DMA-BUF multi-buffer presentation
- Qualcomm KGSL acceleration path
- Freedreno OpenGL integration
- Turnip Vulkan integration
- VirGL compatibility backend
- GPU capability probing
- XWayland application support
- PulseAudio bridge
- Multi-session terminal
- Android soft-keyboard integration
- Chinese composition input
- Mouse, touchpad, scrolling, right-click, and drag gestures
- Desktop shortcut toolbar
- Chinese UTF-8 locale setup
- Android shared-storage mapping
- Fastfetch terminal overview

### Changed

- Browser installation is user-managed
- Desktop applications are synchronized into the desktop environment
- GPU selection rejects unintended software-rendering fallback

### Documentation

- Added bilingual README
- Added architecture documentation
- Added contribution guide
- Added security policy
- Added acknowledgements
- Added GNU GPL v3.0 license