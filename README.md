# LinuxHub

**面向 Android ARM64 的 Linux 桌面与本地 Agent 运行环境**

[English](README_EN.md) · [架构](ARCHITECTURE.md) · [贡献](CONTRIBUTING.md) · [安全](SECURITY.md) · [更新记录](CHANGELOG.md) · [GPL-3.0](LICENSE)

LinuxHub 将完整的 Linux 工作区带入 Android 设备，并把终端、桌面、输入、窗口和 Android 系统操作统一为一套可被本机 Agent 调用的控制接口。用户既可以通过触控界面使用 Linux 桌面，也可以让支持自定义 HTTP Header 的 Agent 直接在设备本地完成自动化任务。

## 核心能力

- 在 Android ARM64 设备上运行 Ubuntu 24.04
- 通过 PRoot 提供 Linux 用户空间，无需修改 Android 系统分区
- 基于 Termux:X11 和 XFCE 的图形桌面
- 离线部署与可追踪的初始化任务
- PulseAudio 音频链路与 Android 共享存储访问
- 面向移动设备的鼠标、键盘和触控映射
- 简体中文语言环境、字体、桌面翻译和中文输入法
- 仅监听本机回环地址的 Agent REST API
- 通过 Shizuku 使用 Android shell、截图、输入、文件和 APK 能力
- 内置 `lotusctl` 命令行客户端

## 本机 Agent

LinuxHub 内置本机 Agent 服务。Agent 可以在不依赖远程桌面端口的情况下访问 Linux 终端、无头桌面和应用窗口，并通过结构化工具完成连续操作。

服务默认配置：

```text
Endpoint: http://127.0.0.1:38473
Authorization: Bearer <在 LinuxHub 设置中复制的 Token>
```

Agent 服务只绑定 `127.0.0.1`。Token 由安全随机数生成，所有工具调用都需要 Bearer 鉴权。

### Agent 工具域

| 工具域 | 能力 |
| --- | --- |
| 终端 | 在 Ubuntu 容器内执行命令，返回退出码、输出、耗时和截断状态 |
| 桌面 | 启动、停止和查询无头 X11/XFCE 会话，读取桌面尺寸与运行状态 |
| 应用 | 枚举 `.desktop` 应用，按应用 ID 启动图形程序 |
| 窗口 | 枚举窗口标题、WM_CLASS、PID、几何和焦点；激活、关闭、移动、缩放、最大化、全屏和等待状态变化 |
| 截图 | 截取完整桌面或指定窗口，并按窗口相对区域裁切 |
| 输入 | 鼠标移动、点击、双击、拖拽、滚轮、按键、组合键和保持按键 |
| 文本 | 向当前 X11 焦点输入 UTF-8 文本 |
| 剪贴板 | 读取、写入和清空 X11 CLIPBOARD |
| 打开与搜索 | 打开绝对路径、URL，并使用用户当前浏览器的默认搜索引擎检索 |
| 批处理 | 顺序执行多项桌面动作，逐项返回结果、耗时和首个失败位置 |
| Android | 通过 Shizuku 执行 shell、截图、输入、文件推拉、APK 安装与卸载以及浏览器角色切换 |
| 系统信息 | 查询应用、Android、ABI、容器和运行环境信息 |

### HTTP 与 CLI

```text
GET  /v1/health
GET  /v1/tools
POST /v1/tools/call
POST /v1/cli/exec
```

容器内提供 `/usr/local/bin/lotusctl`，可直接调用 Agent，无需额外安装 `curl`：

```bash
lotusctl health
lotusctl exec 'uname -a'
lotusctl desktop-start
lotusctl screenshot /tmp/desktop.png
lotusctl call '{"tool":"desktop.windows","arguments":{}}'
```

### 窗口语义控制

Agent 可以直接使用窗口属性完成任务，不需要依赖全屏截图和坐标猜测：

1. 查询应用与窗口。
2. 等待目标窗口出现。
3. 激活唯一匹配的窗口。
4. 通过键盘、文本或相对窗口操作完成任务。
5. 使用礼貌关闭请求退出应用，并验证窗口是否消失。

窗口选择支持 `windowId`、标题、`WM_CLASS` 和 PID。匹配不唯一时，接口返回候选窗口，不会静默选择错误目标。

### Android 本机桥接

Shizuku 为 Agent 提供 shell/ADB 身份，无需 root，也无需启动外部 ADB server。终端、无头桌面和 X11 工具不依赖 Shizuku；需要 Android 系统级能力时，才使用 `android.*` 工具域。

## 中文体验

LinuxHub 将中文环境作为默认桌面的一部分，而不是额外的终端补丁：

- 默认生成 `zh_CN.UTF-8` 语言环境
- 集成 Noto CJK 字体与 Fcitx5 中文输入组件
- 保留 XFCE 终端、文件管理器、面板、窗口管理器和设置程序的简体中文翻译
- 桌面菜单和内置应用提供中文名称与说明
- 终端支持中文显示、组合输入、候选提交和常用编辑按键
- Agent 的 `desktop.text` 支持 UTF-8，可直接向桌面应用输入中文
- Android 软键盘与桌面输入桥使用独立事件通道，Agent 输入不会抢占正常键盘焦点
- 单指长按执行完整的右键按下与释放，并可继续操作上下文菜单

## 桌面与交互

LinuxHub 面向没有实体鼠标和键盘的移动设备设计：

- 单指滑动移动鼠标
- 单指轻点执行左键
- 单指长按执行右键
- 长按后滑动拖拽窗口或内容
- 双指滑动执行滚轮
- 支持 Android 软键盘
- 支持外接鼠标与键盘
- 支持 Backspace、Delete、Tab、Esc、方向键和功能键
- 桌面快捷工具栏提供软键盘与常用按键入口
- 默认 `1280x800`、16:10 桌面区域，关闭强制拉伸
- 首次初始化写入默认壁纸、GTK 主题和图标主题，后续保留用户配置

## Linux 运行环境

- Ubuntu 24.04 ARM64 用户空间
- PRoot 运行层，无需 root
- 离线系统包与完整性校验
- 初始化进度、任务状态与看门狗保护
- XFCE、Termux:X11、D-Bus 与 PulseAudio 会话
- 多终端会话与真彩色终端
- `/sdcard` 映射
- Android 电量与系统环境桥接
- 内置文件管理器、软件包安装器、Chrome 和常用桌面组件

## 安装与启动

1. 安装 LinuxHub APK。
2. 首次启动时创建 Linux 用户。
3. 保持应用在前台，等待系统部署完成。
4. 从首页进入终端或启动 XFCE 桌面。
5. 在设置中启用本机 Agent，并复制 Endpoint 与 Token。

首次部署需要预留足够存储空间。桌面与终端数据保存在应用私有目录中，删除应用数据会同时删除 Linux 环境。

## 构建

要求 JDK 17、Android SDK 和 Android NDK。

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安全边界

- Agent API 只监听 `127.0.0.1`，不向局域网开放。
- 所有工具调用均需要 Bearer Token。
- Token 不写入健康检查响应和运行日志。
- `android.shell` 具有 Shizuku shell 身份，只应向可信 Agent 开放。
- PRoot 是用户空间环境，不是用于运行敌对代码的硬件安全沙箱。
- Linux 程序可能访问显式绑定到容器的 Android 路径，请仅运行可信软件。

安全问题的私密报告方式见 [SECURITY.md](SECURITY.md)。

## 项目与许可

LinuxHub 的基础框架来源于 UP 主 **iPupil**。项目在此基础上持续完善 Android 应用整合、Linux 运行环境、桌面交互、中文支持、终端体验和本机 Agent 能力。

LinuxHub 以 GNU General Public License v3.0 发布。Ubuntu、XFCE、Termux、PRoot、Shizuku、Mesa、PulseAudio 及其他组件分别遵循其原始许可证和版权声明。
