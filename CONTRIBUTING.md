# Contributing to LinuxHub / 参与贡献

感谢你参与 LinuxHub。

## 提交方式

1. Fork 仓库。
2. 从 `main` 创建功能分支。
3. 一次提交只处理一个模块。
4. 修改前先定位相关源码和现有实现。
5. 为行为变更补充测试。
6. 运行单元测试和构建。
7. 提交 Pull Request，并写明实际测试设备。

## 分支命名

- `feature/<name>`：新功能
- `fix/<name>`：问题修复
- `docs/<name>`：文档
- `refactor/<name>`：重构

## 提交信息

推荐使用 Conventional Commits：

```text
feat: add input shortcut
fix: correct evdev backspace mapping
docs: update GNOME architecture
```

## 开发要求

- 不得静默回退到伪硬件加速。
- GPU 后端必须经过实际能力检测，并明确记录最终线路。
- 不得破坏 PRoot、GNOME、X11、Lorie 或终端现有流程。
- 输入修改需检查软键盘、外接键盘、鼠标、触摸板和指针捕获。
- 原生层修改需检查缓冲区、GPU 复制完成和文件描述符生命周期。
- 远程桌面修改需验证 HTTPS、配对码、会话关闭和输入积压处理。
- 修改初始化链路需验证全新安装和 APK 覆盖安装。
- 公共行为变化需同步更新 README、ARCHITECTURE 和 CHANGELOG。

## 构建

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

Rust 组件：

```bash
cargo test --manifest-path rust/linuxhub-sessiond/Cargo.toml
```

## 测试重点

- 断网首次初始化与 `dpkg -C`
- 已安装环境再次启动
- GNOME Shell / Mutter / X11 首帧
- 高通 Adreno 与天玑 Mali GPU 线路
- 终端输入、删除和中文组合输入
- 横屏桌面进入、退出和重启
- Surface 重建与空闲保活
- Chrome、LibreOffice 和 Nautilus 启动
- 外接鼠标、键盘、手写笔和指针捕获
- USB / 局域网远程桌面、配对、H.264 和输入延迟
- APK 覆盖升级与 vendor 应用迁移

## Pull Request

PR 描述应包含：

- 修改目标
- 实现方式
- 测试设备
- Android 版本
- SoC 与 GPU 型号
- 构建结果
- 截图或脱敏日志（适用时）

## License

提交代码即表示你有权贡献该内容，并同意贡献内容按 GNU GPL v3.0 发布。

---

# English

Thank you for contributing to LinuxHub.

1. Fork the repository.
2. Create a focused branch from `main`.
3. Keep each change limited to one module.
4. Locate and inspect the existing implementation before editing.
5. Add tests for behavior changes.
6. Run unit tests and a build.
7. Open a pull request with real device, Android, SoC, and GPU details.

Contributions are accepted under GNU GPL v3.0.
