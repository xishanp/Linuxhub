# LinuxHub

[English](README_EN.md) | 简体中文

LinuxHub 是面向 Android ARM64 设备的离线 Ubuntu 桌面环境，当前基于 Ubuntu 24.04、XFCE、Termux:X11 和 PulseAudio。

## 当前状态

- 显示名：`LinuxHub`
- applicationId：`com.linuxhub.app`
- versionName：`1.1.902`
- versionCode：`70`
- 构建类型：debug（仅用于安装和调试）
- release 签名：尚未配置
- 真机从零初始化：尚未完成最终验收

当前分支是在重做 1.1.9，不是给旧安装流程继续叠加修复。

## 重构方向

- UI 与 Android 集成使用 Kotlin/Java；X11、PRoot 和系统桥接保留 C/C++ 原生运行时。
- 不为了“单一语言”重写已经稳定、且依赖 C ABI 的 X11/PRoot 层。Rust 可作为以后新组件的选择，但不值得在本版本重写底层。
- 离线系统改为一个声明式包池：`tools/offline/package-roots.txt` 是唯一手工包清单，`tools/offline/offline-bootstrap.lock.json` 锁定版本和哈希。
- 初始化只执行一次解压、一次 `dpkg --unpack`、一次 `dpkg --configure -a`，最后用 `dpkg -C` 严格审计。
- 不再使用在线 `apt-get update`、`apt-get -f install`、`|| true` 或分阶段补丁修复来掩盖安装错误。
- 硬件加速核心不在此次重构中改动，Turnip/Zink 保持原有路径。

## 默认系统

默认只安装基础系统、XFCE、X11、音频、输入法、蓝牙、网络管理、文件管理器、Firefox、星火商店和必要运行库。

Python、Node.js、GCC、CMake、Java、Rust、Go、AI 环境和 Jupyter 不作为默认安装项。需要时应作为明确的可选组件安装，而不是重新塞回基础镜像。

桌面默认值只在首次初始化时写入：

- 分辨率：`1280x800`
- 比例：16:10
- 拉伸：关闭
- 自动调整分辨率：关闭
- 壁纸：绿色 Ubuntu 壁纸
- GTK：WhiteSur-Dark
- 图标：Tela Circle

用户之后修改的分辨率、壁纸、主题、面板或图标不会被启动流程反复覆盖。

## 构建

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME='/Users/l/Library/Android/sdk'
sh ./gradlew :app:testDebugUnitTest --no-daemon
sh ./gradlew :app:assembleDebug --no-daemon
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 离线资产

大体量资产不进入 Git：

- `app/src/main/assets/offline/bootstrap-arm64.tar`
- `app/src/main/assets/offline/vendor-apps-arm64.tar`
- `app/src/main/assets/rootfs/ubuntu-base-24.04.4-base-arm64.tar`

生成和发布说明见 [OFFLINE_ASSETS.md](OFFLINE_ASSETS.md)。

## 安装与限制

- 仅支持 `arm64-v8a`。
- 首次初始化预留至少 5 GB 可用空间。
- 初始化期间保持应用在前台，不要清理后台或强制退出。
- 首次初始化目标时间为 5–6 分钟，最终以真机实测为准。
- 当前 debug APK 不能作为正式发布包；发布前需要单独配置 release keystore 和签名。
- 换包名后旧包数据不会自动继承，数据迁移策略尚未实现。

## 验收重点

- 断网从零初始化是否一次通过。
- 583 个 bootstrap 包的 `dpkg -C` 审计是否为空。
- Firefox `ldd` 是否没有 `not found`。
- XFCE、X11、音频、蓝牙、网络管理和输入法是否正常。
- 16:10 是否无拉伸，首次默认值与后续用户修改是否互不干扰。
- Turnip/Zink 正常路径及软件回退。
- 改包名后的 Shizuku 重新授权和 X11 service 绑定。
- 初始化耗时、rootfs 占用和内存峰值。

## 许可证

发布前必须核对代码、主题、图标、驱动、Firefox、星火商店及其他预装组件的许可证和分发条款。
