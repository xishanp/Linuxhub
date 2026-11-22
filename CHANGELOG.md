# Changelog

All notable changes to LinuxHub are documented here.

The format follows Keep a Changelog. Public release names use the project mythology scheme; `versionCode` remains the Android upgrade-order integer.

## [2.0.0-myth-prometheus] - 2026-11-22

### Desktop

- Ubuntu 24.04.4 ARM64 with official GNOME Shell 46 and Mutter 46 X11.
- Lorie X11 and PRoot run the desktop locally on Android without a virtual machine.
- Chinese environment, Yaru theme, Ubuntu Dock, desktop icons, and window management.
- Nautilus, GNOME Settings, Terminal, Text Editor, screenshot tools, LibreOffice, Chrome, Thunderbird, Evince, Remmina, Transmission, Rhythmbox, Totem, Shotwell, Disks, System Monitor, and Archive Manager.
- Offline GNOME payload with 729 official Ubuntu DEBs covering desktop components and dependencies.
- Dedicated LinuxHub GNOME session mode, private D-Bus, first-frame diagnostics, stop, restart, and failure-log collection.

### Compatibility hooks and bridges

- PRoot `ptrace` / `seccomp` interception, path rewriting, `fake_id0`, UID / GID mapping, and ashmem-to-memfd conversion.
- Recovery for Android seccomp-denied syscalls and PRoot memfd read / write mode handling.
- Rootfs symlink, hardlink, SUID / SGID, and merged `/usr` repair.
- `LD_PRELOAD` getifaddrs hook with a Rust Android helper, private Unix socket, and length-prefixed `TIF1` protocol.
- IPv6 `sockaddr_in6` truncation, short-read, disconnect, and data-coalescing fixes.
- Automatic libc fallback when the network bridge is unavailable.
- Client refresh from the APK on every terminal and desktop start.
- GNOME `G_RESOURCE_OVERLAYS`, session compatibility resources, and DING clipboard / file drag-and-drop fixes.
- Unified `doro-gpu-run` application launcher with `doro-chrome` Chrome wrapper.
- Xserver Present, VBlank, Damage, buffer submission, GPU-copy completion, buffer recycling, and stale-frame patches.
- Direct X11-to-MediaCodec Surface presentation and one-frame-per-second idle keepalive.
- PulseAudio `module-pipe-sink` to FIFO to Android `AudioTrack` audio bridge.
- Android battery status in the GNOME top bar.

### Downloads, networking, and installation

- Chrome as the default handler for `http`, `https`, and HTML files.
- Normal and incognito Chrome Desktop Actions.
- Standard XDG Downloads, Documents, Pictures, Music, and Videos directories with Chinese GTK names.
- `/storage/emulated/0`, `/sdcard`, and `/sdcard/Download` bindings.
- Bidirectional file exchange between Android and Ubuntu.
- Offline OpenSSL and CA certificates with automatic trust-store generation.
- `/etc/hosts`, hostname, and localhost resolution fixes.
- Ubuntu 24.04 `noble` APT source pinning.
- LinuxHub `.deb` installer association, ARM64 and package validation, and APT installation.
- APT `Post-Invoke` launcher refresh and automatic GPU / MIME metadata for third-party applications.
- Content-addressed vendor application migration during APK overwrite upgrades.
- Two-stage `doro-fetch` terminal card for narrow screens with a plain-text fallback.

### GPU and browser

- Qualcomm Adreno Mesa Zink / Turnip application rendering.
- MediaTek Mali VirGL / virpipe application rendering.
- GNOME Shell / Mutter on llvmpipe for compositor stability.
- Automatic llvmpipe fallback when a vendor GPU path fails.
- Mesa 27 Qualcomm AArch64 CPU feature detection, SVE `SIGILL`, and first-frame timeout fixes.
- `GSK_RENDERER=cairo` on affected Qualcomm GTK4 applications to fix corrupted tabs and damaged regions.
- Chrome GPU acceleration for Canvas, Compositing, Rasterization, Video Decode, WebGL, and WebGPU.
- Hardware video encoding deferred to the next release.

### Remote desktop

- Browser-based USB and LAN remote desktop without a client installation.
- HTTPS, self-signed certificate, and six-digit pairing code.
- macOS and Windows one-command connection helpers.
- X11 directly into a MediaCodec Surface with Android hardware H.264 encoding.
- Browser WebCodecs decoding.
- Keyboard, mouse, touch, wheel, and dynamic frame-size support.
- Latency- and queue-aware bitrate control.
- Stale-frame dropping, keyframe requests, and `drop-input-frames` for congested input queues.

### Initialization and upgrades

- Guided permissions, GPU route, username, and password setup.
- Per-task completion, failure, timeout, and diagnostic messages.
- Total timeout and no-output process watchdog.
- Recoverable state after cancellation or failure.
- APK overwrite upgrades preserve rootfs, user directories, and configuration.
- Automatic GNOME, Mesa, and vendor application reconciliation.
- Runtime resource refresh by APK content version without requiring a new initialization.
- Removal of obsolete components from older releases.
- At least 5 GB free-space recommendation for first initialization.

### Android control center and interface

- LinuxHub dashboard with Ubuntu version, user, device, architecture, and GPU route.
- Live memory, storage, network, and desktop status.
- Desktop resolution, uptime, and remote connection count.
- Direct access to Terminal, Desktop, USB Remote, LAN Remote, and About.
- Liquid-glass cards, local glass toolbars, and a transparent LinuxHub mark.
- Foreground desktop notification with an immediate force-stop action.
- Picture-in-picture, fullscreen, orientation, and display-cutout support.
- Keep-awake control and Android user-activity refresh every 120 seconds.

### X11 input and display settings

- Native, scaled, exact, and custom desktop resolutions.
- Nearest-neighbor and bilinear filtering.
- Compressed display while the keyboard is open.
- Touchpad, simulated-touch, and direct-touch modes.
- Pointer speed, pointer capture, and capture-direction transforms.
- Stylus-as-mouse support.
- Hardware keyboard, scancode, DeX Meta key, and character input.
- Clipboard synchronization.
- Configurable swipe-up, swipe-down, and Back actions.

### Stability fixes

- Qualcomm vendor GID names filled in `/etc/group`.
- `groups: cannot find name` error after `su` fixed.
- Oplus three-second Surface buffer warning fixed with Lorie idle keepalive.
- GNOME first-frame timeout and invisible-window failure fixed.
- X11 process cleanup after desktop stop fixed.
- Initialization watchdog latest-activity timestamp fixed.
- Chrome and LibreOffice launch environment and shared-library conflicts fixed.
- Rust `linuxhub-sessiond` now owns startup planning, environment checks, helper orchestration, and session restart.
- Rust `getifaddrs-bridge` replaces the legacy C bridge.
- Release builds force the current Xlorie native library to avoid packaging an old copy.

### Release

- Display name: `LinuxHub`
- GNOME desktop family: `Myth`
- Version: `Myth-Prometheus`
- applicationId: `com.linuxhub.myth`
- versionCode: `201`
- Architecture: ARM64 only
- Signing: APK Signature Scheme v3
- APK size: approximately 990 MB
- Distribution: GitHub Releases

### Boundaries

- Browser hardware video encoding is deferred to the next release.
- Production Chrome Wayland integration is deferred to the next release.
- AAC / MP4 packaging is deferred to the next release.
- The Canvas 2D left-edge artifact under Wayland / Zink remains unresolved.
