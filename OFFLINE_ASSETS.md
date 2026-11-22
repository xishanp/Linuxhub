# Offline assets / 离线资源

LinuxHub 2.0 使用两层离线部署：先展开 Ubuntu 24.04.4 base rootfs、GNOME、Mesa 和系统依赖，再部署 Chrome、LibreOffice、Thunderbird 等 vendor 应用。初始化不依赖现场访问 Ubuntu APT 源。

## Release payload / 发布包内容

| Asset / 资源 | Purpose / 用途 |
| --- | --- |
| `ubuntu-base-24.04.4-base-arm64.tar` | Ubuntu 24.04.4 ARM64 base rootfs |
| `gnome-24.04-arm64.tar` | Official Ubuntu GNOME DEB set / 官方 Ubuntu GNOME DEB 集合 |
| `gnome-packages.txt` | GNOME package manifest / GNOME 软件包清单 |
| `bootstrap-arm64.tar` | Bootstrap packages and base runtime dependencies / 基础包与运行时依赖 |
| `bootstrap-packages.txt` | Bootstrap package manifest / 基础包清单 |
| `vendor-apps-arm64.tar` | Chrome, LibreOffice, Thunderbird, and other desktop applications / Chrome、LibreOffice、Thunderbird 等桌面应用 |
| `vendor-packages.txt` | Vendor application package manifest / vendor 应用清单 |
| `vendor-dependencies.txt` | Vendor application dependency manifest / vendor 应用依赖清单 |
| `vendor-app-tasks.tsv` | Content-addressed vendor application migration tasks / 按内容指纹执行的 vendor 应用迁移任务 |
| `mesa-for-android-container_27.0.0-devel-20261025-r2_ubuntu_noble_arm64.tar` | Mesa 27, Zink / Turnip / VirGL / llvmpipe runtime / Mesa 27 与图形运行库 |
| `certificates/openssl_3.0.13-0ubuntu3.16_arm64.deb` | Offline OpenSSL / 离线 OpenSSL |
| `certificates/ca-certificates_20260601~24.04.1_all.deb` | Offline CA certificate bundle / 离线 CA 证书 |
| `fastfetch/fastfetch-linux-aarch64.deb` | Terminal system-information card / 终端系统信息卡 |
| `sudo/sudo_1.9.15p5-3ubuntu5.24.04.4_arm64.deb` | sudo compatibility package / sudo 兼容包 |
| `librsvg/librsvg2-common_2.58.0+dfsg-1ubuntu0.1_arm64.deb` | SVG rendering support / SVG 渲染支持 |
| `common/libatomic1_14.2.0-4ubuntu2~24.04.1_arm64.deb` | Runtime library dependency / 运行时库依赖 |

## GNOME package set / GNOME 包集合

`tools/offline/offline-gnome.lock.json` records `package_count: 729`. The archive contains official Ubuntu 24.04 ARM64 and `all` packages, including GNOME Shell 46, Mutter 46, GJS, GNOME Settings, Nautilus, GNOME Terminal, Text Editor, Yaru, fonts, Chinese input support, XDG tools, and desktop dependencies.

`tools/offline/offline-gnome.lock.json` 记录 `package_count: 729`。归档包含 Ubuntu 24.04 官方 ARM64 与 `all` 软件包，覆盖 GNOME Shell 46、Mutter 46、GJS、GNOME 设置、Nautilus、GNOME Terminal、文本编辑器、Yaru、字体、中文输入支持和 XDG 工具等桌面依赖。

## Integrity and updates / 完整性与升级

- Release APK contains the runtime payload required for offline initialization.
- APK overwrite upgrades compare content fingerprints and install new or updated vendor applications.
- Runtime resources are refreshed by APK content version rather than forcing a full reinitialization.
- Initialization validates free space, package manifests, architecture, and package installation results.
- At least 5 GB of free storage is recommended.

- Release APK 内含离线初始化所需的运行资源。
- APK 覆盖升级会比对内容指纹，并补装新增或更新的 vendor 应用。
- 运行资源按 APK 内容版本刷新，不强制重新初始化。
- 初始化会检查可用空间、软件包清单、架构和安装结果。
- 建议至少预留 5 GB 可用空间。

## Source repository / 源码仓库

Large runtime archives are distributed through the release APK and are not intended to be duplicated into every source checkout. Do not replace a release APK with a source archive containing placeholder or partial files.

大型运行归档通过 Release APK 分发，不应在每个源码检出中重复提交。不要用包含占位文件或不完整文件的源码归档替换正式 APK。
