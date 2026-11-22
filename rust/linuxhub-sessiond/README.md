# linuxhub-sessiond

Isolated Rust control-plane prototype for LinuxHub 2.0.

Scope:

- Ubuntu 24.04 ARM64 / official GNOME Shell 46 / Mutter 46 X11 session plan.
- Direct Shell launch with a private D-Bus session.
- LLVMpipe-first environment.
- Capability reporting for system D-Bus, logind, Polkit, UPower, NetworkManager, portals, and renderer.
- Supervisor policy for stop/restart.

Out of scope:

- Replacing GNOME Shell, Mutter, GJS, or the Xserver.
- Claiming logind/Polkit/systemd are implemented.
- Becoming the active APK launch path before ARM64 device validation.

Run locally:

```bash
cargo test --manifest-path rust/linuxhub-sessiond/Cargo.toml
cargo run --manifest-path rust/linuxhub-sessiond/Cargo.toml -- :0 doro
```
