# LinuxHub GNOME 46 runtime sources

This directory is the LinuxHub-owned downstream source boundary for the
Ubuntu 24.04 ARM64 GNOME 46 runtime.

The runtime is not maintained as an overlay of startup-time shims. Ubuntu
source packages are imported into standalone Git repositories, then modified
directly on LinuxHub branches. Built packages receive a `+linuxhubN` version
suffix and must retain their Ubuntu source/version provenance.

## Components

| Component | Ubuntu source version | Downstream branch |
|---|---|---|
| gnome-shell | `46.0-0ubuntu6~24.04.17` | `linuxhub/ubuntu-noble` |
| mutter | `46.2-1ubuntu0.24.04.18` | `linuxhub/ubuntu-noble` |
| gjs | `1.80.2-1build2` | `linuxhub/ubuntu-noble` |

Exact upstream commits and source archive hashes are recorded in
`sources.lock.json`.

## Layout

```text
runtime/gnome-46/
  README.md
  sources.lock.json
  gnome-shell/     local Git source tree
  mutter/          local Git source tree
  gjs/             local Git source tree
  build/           ARM64 package build workspace
  artifacts/       LinuxHub-built DEBs
```

Component source trees and build outputs are intentionally ignored by the
Android app repository. They remain full Git repositories under their own
branch and history.

## Rules

- Do not copy Termux/Bionic binaries or shims into this tree.
- Do not modify installed rootfs files at launch time as a substitute for a
  source change.
- Record the failing device log/reproduction before making a compatibility
  change.
- Keep the stock Ubuntu DEB pool as a diagnostic control and rollback path.
- Do not put LinuxHub Android UI code into GNOME. Integrate at explicit session,
  D-Bus, application-launch, input, clipboard, audio, and file-bridge seams.

## Synchronize

```bash
tools/source/sync_gnome46_sources.sh
```

The script is idempotent for an unmodified checkout. It does not overwrite a
dirty downstream worktree.
