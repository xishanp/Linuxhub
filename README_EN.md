# LinuxHub

**A Linux desktop and local Agent runtime for Android ARM64 devices**

[简体中文](README.md) · [Architecture](ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md) · [GPL-3.0](LICENSE)

LinuxHub brings a complete Linux workspace to Android and unifies its terminal, desktop, input, window, and Android system operations behind one local control surface for AI agents. Users can work through the touch-first Linux desktop or let an agent with custom HTTP header support perform automation directly on the device.

## Highlights

- Ubuntu 24.04 on Android ARM64 devices
- PRoot-based Linux userspace without modifying Android system partitions
- XFCE desktop delivered through Termux:X11
- Offline deployment with traceable initialization tasks
- PulseAudio integration and Android shared-storage access
- Touch, mouse, and keyboard interaction designed for mobile devices
- Simplified Chinese locale, fonts, desktop translations, and input method support
- Loopback-only Agent REST API
- Android shell, screenshots, input, files, and APK operations through Shizuku
- Built-in `lotusctl` command-line client

## Local Agent

LinuxHub includes a built-in local Agent service. It provides structured access to the Linux terminal, headless desktop, application windows, and Android system capabilities without exposing a remote desktop port.

Default connection:

```text
Endpoint: http://127.0.0.1:38473
Authorization: Bearer <token copied from LinuxHub settings>
```

The service binds only to `127.0.0.1`. Its token is generated from a cryptographically secure random source, and every tool call requires Bearer authentication.

### Tool domains

| Domain | Capabilities |
| --- | --- |
| Terminal | Execute commands inside the Ubuntu container with exit code, output, timing, and truncation metadata |
| Desktop | Start, stop, and inspect headless X11/XFCE sessions and display dimensions |
| Applications | Enumerate `.desktop` applications and launch graphical programs by application ID |
| Windows | List titles, WM_CLASS values, PIDs, geometry, and focus; activate, close, move, resize, maximize, fullscreen, and wait for state changes |
| Screenshots | Capture the full desktop or a selected window, including window-relative crops |
| Input | Move, click, double-click, drag, scroll, press keys, send shortcuts, and hold keys |
| Text | Enter UTF-8 text into the current X11 focus |
| Clipboard | Read, write, and clear the X11 CLIPBOARD |
| Open and search | Open absolute paths and URLs, or search with the browser's current default search engine |
| Batch | Execute desktop actions in order and return per-action results, timing, and the first failure |
| Android | Use Shizuku for shell access, screenshots, input, file transfer, APK installation/removal, and browser-role changes |
| System | Query application, Android, ABI, container, and runtime information |

### HTTP and CLI

```text
GET  /v1/health
GET  /v1/tools
POST /v1/tools/call
POST /v1/cli/exec
```

The container includes `/usr/local/bin/lotusctl`:

```bash
lotusctl health
lotusctl exec 'uname -a'
lotusctl desktop-start
lotusctl screenshot /tmp/desktop.png
lotusctl call '{"tool":"desktop.windows","arguments":{}}'
```

### Semantic window control

Agents can operate windows through semantic selectors instead of relying on full-screen screenshots and coordinate guessing:

1. Discover applications and windows.
2. Wait for the target window to appear.
3. Activate the unique matching window.
4. Complete the task with keyboard, text, or window-relative input.
5. Send a polite close request and verify that the window has disappeared.

Selectors support `windowId`, title, `WM_CLASS`, and PID. If a selector matches multiple windows, the API returns the candidates instead of choosing an arbitrary target.

### Android bridge

Shizuku provides a shell/ADB identity without root and without an external ADB server. Terminal, headless desktop, and X11 tools do not depend on Shizuku; the `android.*` tools use it only when Android system-level access is required.

## Chinese experience

LinuxHub treats Simplified Chinese as part of the default desktop experience:

- Generates a `zh_CN.UTF-8` locale by default
- Includes Noto CJK fonts and Fcitx5 Chinese input components
- Preserves Simplified Chinese translations for XFCE Terminal, Thunar, the panel, window manager, and settings applications
- Provides Chinese names and descriptions for desktop menus and built-in applications
- Supports Chinese rendering, composition, candidate selection, and editing keys in the terminal
- Allows Agent `desktop.text` calls to enter Chinese through UTF-8
- Keeps Android soft-keyboard and desktop input events on independent paths so Agent input does not take over normal keyboard focus
- Converts a one-finger long press into a complete right-button press and release, including context-menu interaction

## Desktop interaction

LinuxHub is designed for mobile devices without a physical mouse or keyboard:

- One-finger movement for cursor control
- Tap for left click
- Long press for right click
- Long press and move to drag
- Two-finger scrolling
- Android soft-keyboard support
- External mouse and keyboard support
- Backspace, Delete, Tab, Escape, arrow-key, and function-key mapping
- A persistent shortcut toolbar for the soft keyboard and common keys
- A default `1280x800`, 16:10 desktop area without forced stretching
- First-run defaults for wallpaper, GTK, and icon themes while preserving later user changes

## Linux runtime

- Ubuntu 24.04 ARM64 userspace
- PRoot runtime without root
- Offline packages with integrity verification
- Initialization progress, task state, and watchdog protection
- XFCE, Termux:X11, D-Bus, and PulseAudio sessions
- Multi-session, true-color terminal
- `/sdcard` storage mapping
- Android battery and environment bridges
- File manager, package installer, Chrome, and essential desktop components

## Getting started

1. Install the LinuxHub APK.
2. Create a Linux user on first launch.
3. Keep LinuxHub in the foreground while the system is deployed.
4. Open the terminal or start the XFCE desktop from the home screen.
5. Enable the local Agent in Settings and copy its Endpoint and Token.

Reserve sufficient storage before the first deployment. The Linux environment is stored in the application's private data directory; clearing application data also removes the Linux environment.

## Build

JDK 17, the Android SDK, and the Android NDK are required.

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Security boundaries

- The Agent API listens only on `127.0.0.1` and is not exposed to the LAN.
- Every tool call requires a Bearer token.
- Tokens are excluded from health responses and runtime logs.
- `android.shell` carries the Shizuku shell identity and must only be exposed to trusted agents.
- PRoot is a userspace environment, not a hardware security sandbox for hostile code.
- Linux programs may access Android paths explicitly bind-mounted into the container. Run trusted software only.

See [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## Credits and license

The foundational framework originates from content creator **iPupil**. LinuxHub builds on that foundation with Android integration, the Linux runtime, desktop interaction, Chinese language support, terminal experience, and local Agent capabilities.

LinuxHub is released under the GNU General Public License v3.0. Ubuntu, XFCE, Termux, PRoot, Shizuku, Mesa, PulseAudio, and other components remain subject to their respective licenses and copyright notices.
