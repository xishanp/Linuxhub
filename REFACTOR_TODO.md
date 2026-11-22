> **历史文件：这是 LinuxHub 1.x 的重构记录，不是 2.0 执行计划。2.0 只以 `docs/research/linuxhub-2.0/00-GOAL_AND_PROGRESS.md` 和 `06-ubuntu-24.04-gnome46-x11-route.md` 为准。**

# LinuxHub 1.1.907 重构记录

更新时间：2026-10-25 CST
分支：`rebuild/1.1.9`
基线提交：`9106a0b8`

## 已确认决定

- 显示名：`LinuxHub`
- 版本名：`1.1.907`
- versionCode：`75`
- 最终 applicationId：`com.linuxhub.app`
- Gradle namespace 暂保留 `com.doro.terminal.shell`，只改安装包身份，避免无收益的全目录重命名。
- 当前只产出 debug 签名 APK；release keystore 和正式签名留到发布前。
- 继续使用 Ubuntu 24.04 LTS；版本完成并实测后，再根据数据决定是否评估 Debian 13。
- 不修改硬件加速、Mesa、Turnip/Zink 的核心逻辑。
- 默认 16:10、绿色壁纸、Tela Circle 图标和 WhiteSur GTK 主题保留。
- 默认桌面值只在首次初始化写入，后续不覆盖用户修改。
- 初始化期间不执行在线 `apt-get update` 或 `apt-get -f install`。

## 本轮已完成

### 应用身份

- [x] `applicationId` 改为 `com.linuxhub.app`。
- [x] Manifest 的 Shizuku authority 使用 `${applicationId}.shizuku`，会跟随新包名。
- [x] X11 服务显式类名保持 `com.doro.terminal.shell.x11.X11ServerService`。
- [x] 仪器测试中的应用包名断言同步改为 `com.linuxhub.app`。
- [x] debug APK 构建成功，包名、版本号和 debug 签名已核验。

### 离线包架构

- [x] 删除分散的系统工具、CLI、桌面、星火依赖、GPU 工具、Firefox 依赖补丁层。
- [x] 改为单一离线包池 `offline/bootstrap-arm64.tar`。
- [x] 唯一手工输入为 `tools/offline/package-roots.txt`。
- [x] 生成器从 Ubuntu noble/updates/security arm64 索引解析完整 Depends/Pre-Depends 闭包。
- [x] 使用锁文件 `tools/offline/offline-bootstrap.lock.json` 固定包名、版本、架构、SHA256、来源和依赖。
- [x] 生成器只复用名称和版本完全匹配的旧 DEB；不再按包名盲目放行旧版本。
- [x] 生成 TAR 为确定性输出；连续两次重建的 SHA256 一致。
- [x] 当前结果：514 个根包，解析后 594 个包，TAR 332 MiB。
- [x] bootstrap TAR SHA256：`776e5ffe4d8225c6c67aca0728799058a2c8826f4f4865f528ec315950ec8db9`
- [x] 594 个包的 TAR 内容、清单和锁文件 SHA256 全部一致。
- [x] Firefox、Mesa 与 rootfs 资产改为 gzip 后仍沿用 `.tar` 文件名，运行时由 TarGzExtractor 自动识别；APK/分发体积显著下降。

### 安装流程

- [x] 初始化改为一次解压，再按 16 层依赖批次逐批执行完整 `dpkg -i`，最后执行 `dpkg --configure -a`。
- [x] 安装后使用 `dpkg -C` 做最终依赖审计，不再用 `|| true` 吞错。
- [x] 删除 `FactoryPackageRepairScript`、旧 vendor repair 路径和 factory-fix DEB 补丁。
- [x] Firefox 运行库由总包池提供；vendor 包只保留 Firefox 本体和桌面入口。
- [x] 移除星火商店；保留常用 CLI、蓝牙、网络管理、X11、XFCE、GPU 诊断工具和文件管理器。
- [x] 继续裁剪 Python/AI 等非基础预设，系统只保留必要运行库和用户明确保留的预设。

### 默认桌面

- [x] 默认分辨率 `1280x800`，固定 16:10。
- [x] `displayStretch=false`、`adjustResolution=false`。
- [x] 绿色壁纸、Tela Circle、WhiteSur GTK 作为首次默认值。
- [x] 用户已有桌面配置不因重启恢复成预设。
- [x] 主题资产安装移到初始化阶段，启动桌面时不再删除用户后来安装的 WhiteSur 图标包。
- [x] 启动图标保持深色底和绿色扁平风格，将原终端 `>_` 符号替换为绿色 Ubuntu 圆环标志。
- [x] 更多权限页显示可读取的电池优化和系统后台限制；ColorOS 自启动白名单不开放读取接口，页面明确提示只能进入系统页确认，不再伪装成已开启。

### 启动链路修复

- [x] 修复 Compose 初始化回调使用旧闭包状态的问题；创建用户后直接使用表单提交的用户名和密码，不再传入空用户名/密码。
- [x] 初始化页面文案改为 5–6 分钟目标时间，并保留真实进度和 watchdog。
- [x] 更新 `README.md`、`README_EN.md` 和 `OFFLINE_ASSETS.md`，移除旧版“预装开发环境”“30 分钟”“WhiteSur 图标”等失实说明。

### Lotus 本机 Agent

- [x] 增加仅绑定 `127.0.0.1:38473` 的前台 Agent 服务，使用随机 32 字节 Bearer Token。
- [x] 统一接口：`/v1/health`、`/v1/tools`、`/v1/tools/call`，以及供容器 CLI 使用的 `/v1/cli/exec`。
- [x] 首批工具覆盖容器终端、无头 X11/XFCE 启停与状态、根窗口截图、鼠标、键盘、文本和 Shizuku 状态。
- [x] 无头 X11 使用 Lorie 的 headless 连接，不依赖可见 Activity；桌面默认 `1280x1024`，可调用时指定尺寸。
- [x] 设置页增加“Lotus的电脑”，展示 Endpoint、Token、服务/桌面状态和 `https://github.com/xishanp/LotusAI`。
- [x] 启动时安装容器内 `/usr/local/bin/lotusctl` 和 `/etc/lotus-agent/token`，调用不依赖 curl。
- [x] `lotusctl` 脚本通过 Bash `/dev/tcp` 直接调用本机接口，已通过 `bash -n` 语法检查。
- [x] 接口文档写入 `docs/LOTUS_AGENT_API.md`。
- [x] 增加 shell-only 后台唤醒入口：Agent 服务对 `android.permission.DUMP` 持有者导出，ADB/Shizuku shell 可在进程被系统回收后重新启动服务；普通第三方应用无权限启动。
- [x] App 每次返回前台都会重新同步 Agent 前台服务；无头桌面开关即时生效，不再只写 SharedPreferences 等下次冷启动。
- [x] 移除嵌入桌面没有消费者的 9 个 Termux:X11 设置项，避免开关显示存在但实际无动作；音频、指针、显示和剪贴板等设置保留真实映射。

#### Lotus Agent 2026-10-25 增强

- [x] 标准 MCP/HTTPS 实验已撤销：本机回环、单条 `https://` URL、零客户端证书预配置和鉴权无法同时满足，不再保留假 HTTPS 或自签证书路线。
- [x] 增加 `desktop.windows/activate/close/wait`：窗口 ID、标题、WM_CLASS、PID、几何、焦点、EWMH 状态、礼貌关闭和状态等待。
- [x] 增加 `desktop.clipboard`：读取、写入、清空 X11 CLIPBOARD；XFCE 会话内常驻 owner，工具退出后内容仍可用。
- [x] 增加 `desktop.window`：移动、缩放、设置几何、最大化/还原、全屏、最小化和恢复；操作后回读窗口状态验证。
- [x] `desktop.screenshot` 支持窗口选择器和相对窗口的 `region` 裁切，减少全屏图片进入 Agent 上下文。
- [x] 增加 APK-owned `/usr/local/bin/doro-x11-control`，用 Python `ctypes` 直连 `libX11.so.6`，不依赖 `xdotool/wmctrl/xprop` 或在线安装。
- [x] `desktop.batch` 支持窗口动作、剪贴板和 `stopOnError`，逐项返回结果、耗时、精确失败索引；JSON `ok=false` 明确表示整体失败。
- [x] 增加 `android.default_browser`，Android BROWSER role 真机切换为 `com.android.chrome`。
- [x] `desktop.search` 从当前 Chrome profile 读取默认搜索引擎；用户更换 Bing/Google 等引擎后，Agent 使用同一配置。
- [x] 修复 XFCE 会话脚本两处非法 `&;`；此前会导致 `dbus-launch/startxfce4` 未执行，只剩 X server 和鼠标。
- [x] 真机验收窗口：Chrome 与 XFCE Terminal 启动等待、窗口枚举、焦点切换、礼貌关闭和消失等待全部通过。
- [x] 真机验收剪贴板：`desktop.clipboard set/get` 写入 29 bytes 后读取一致。
- [x] 真机验收批处理：3 项动作全部成功；注入无效窗口后 `ok=false`、`failedActionIndex=1`、只返回两个已执行动作。
- [x] 真机验收窗口调整与裁切：Chrome 从 `(689,40)` 移动到 `(729,40)` 后恢复；窗口区域截图返回 `600x400`。
- [x] 连接信息页面恢复为单一 REST Endpoint 和 Bearer Token，只监听 `127.0.0.1:38473`。
- [x] 最新单元测试：87 tests，0 failure、0 error、0 skipped；`:app:assembleDebug` 通过。
- [x] 最新真机证据截图：`/tmp/linuxhub-window-control-final.png`，桌面 `2360x1080`，Chrome 正常显示且 Terminal 已礼貌关闭。

### ADB / Shizuku 本机控制桥（2026-10-24 固定需求）

- [x] 不做无线调试配对、不生成/保存 App 专属 ADB key、不启动外部 adb server；Shizuku 是唯一 Android 特权入口。
- [x] 通过 Shizuku 的 shell/ADB 身份执行本机 Android 命令，不要求 root；已验证服务 UID `2000`、SELinux `u:r:shell:s0`。
- [x] 终端内提供 ADB 兼容入口：`devices`、`get-state`、`shell`、`exec-out screencap -p`、`input`、`push`、`pull`、`install`、`uninstall`。
- [x] 所有结构化操作使用 argv；只有显式 `shell` 子命令接受完整 shell 文本，文档已明确其 shell UID 权限语义。
- [x] 命令具备超时、输出上限、并发上限、stdout/stderr/exit code 和审计日志；日志不写 Token 或密钥。
- [x] Agent 工具与容器内 `/usr/local/bin/lotusctl`、`/usr/local/bin/adb` 共用同一 `ShizukuHostBridge` 后端。
- [x] 真机验收：`android.shell` 返回 UID 2000；Android 截图成功；容器 `adb shell id` 返回 UID 2000；推拉文件 SHA256 一致；输入和 APK 安装/卸载已验证。
- [x] 无头桌面真实可用：XFCE 后台启动、根窗口截图、`desktop.launch_app` 启动 XFCE About/Chrome 并确认窗口与网络正常。
- [x] Agent 工具清单中的 Android/桌面工具可调用；失败返回真实错误，不伪造成功。
- [x] 液态玻璃、模糊、折射、描边、颜色、顶栏和设置页视觉保持冻结，本任务不改这些 UI 材质。

#### 2026-10-25 真机回归证据

- 设备：`PLE110`，Android 16；APK `1.1.906 (74)`，包名 `com.linuxhub.app`。
- Shizuku：`shizukuRunning=true`、`permissionGranted=true`、`serviceUid=2000`、`shellIdentity=true`。
- 容器内 `adb shell 'id; getprop ro.product.model; echo container-adb-ok'`：UID `2000`、型号 `PLE110`、退出码 `0`。
- 容器内 `adb devices`：`LinuxHubShizuku device`；`adb get-state`：`device`；不会再把 host-status JSON 混进 devices 输出。
- 容器内 `adb exec-out screencap -p`、`lotusctl android-screenshot` 和 `lotusctl screenshot` 均生成有效 PNG；修复了 Base64 中 `\/` 的 JSON 转义导致的解码失败。
- 无头桌面 stop/start 连续 5 轮全部通过；每轮停止后隔离 `:x11` 进程为 `none`，重启后 `RUNNING_HEADLESS`、截图均为 `2360x1080` PNG。
- 无头桌面启动 `xfce4-about.desktop` 成功，截图确认 XFCE About 窗口可见；Chrome 的无头启动和 HTTP 网络已有验证记录。
- 容器文件 → Android `/sdcard/Download` → 容器文件：两端 SHA256 `0035a03454947a1a1046de9be0bcefe35a7bb155a3f05d086e148c3a4f56a8a9`。
- APK 安装/卸载测试包 `com.linuxhub.adbtest`：安装、确认存在、卸载、确认不存在；ColorOS 可能要求系统安装确认，不宣称静默安装。
- 2026-10-25 最终冒烟：`/v1/health` 返回 `agentRunning=true`；`android.shell` 返回 UID `2000`；容器 `adb devices/get-state/shell` 通过；Android 截图 `1080x2362`、无头桌面截图 `2360x1080` 均为有效 PNG；无头桌面启动 XFCE About 后可截图确认；容器→Android→容器往返文件 SHA256 均为 `57caca41a50ffc510f43c0ad181c39dc762484882d1b3d44fd89ec2d59d89f1f`。
- 2026-10-25 测试：`testDebugUnitTest` 共 212 项，0 failure、0 error、0 skipped。
- 修复了停止后复用已污染 `:x11` 原生状态导致的随机 SIGSEGV/SIGABRT；现在服务销毁时主动退出隔离进程，下一轮从干净进程启动。
- `libXlorie.so` 必须包含 `captureFrameSize`/`captureFrameNative`；修改 native 后使用 `-PrebuildX11Native=true` 或在验证后刷新 `jniLibs-fallback`。

## 构建验证

- `:app:testDebugUnitTest`：通过。
- `:app:assembleDebug`：通过。
- debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 当前 debug APK：673,147,554 bytes（约 642 MiB）。
- SHA256：`acdf3ddac05077bf5f1365336fb1b9d811af51e9d352c163aaa41ca387ae14d2`。
- APK 使用 Android debug certificate，未使用 release key。
- `unzip -l` 列出的条目总量：585,295,055 bytes；共 663 个 ZIP 条目。

## 待真机验证

- [x] 安装新包 `com.linuxhub.app`，真机验证版本 `1.1.906 (74)`。
- [ ] 断网从零初始化，确认 594 个包安装和 `dpkg -C` 审计通过。
- [ ] Firefox 可启动，`ldd` 无 `not found`。
- [ ] XFCE、X11、PulseAudio、输入法、蓝牙、网络管理正常。
- [ ] 16:10 无拉伸，壁纸和 Tela Circle 默认值正确。
- [ ] 用户修改默认桌面后重启不被覆盖。
- [ ] Turnip/Zink 硬件加速路径与重构前一致，并验证软件回退。
- [ ] Cursor/Electron 在 PRoot 下同时验证沙箱参数与 ZINK GPU 渲染路径；禁止用全局软件渲染掩盖问题。
- [ ] 统计初始化耗时、rootfs 占用、桌面空闲内存和启动峰值。
- [ ] 改包名后的 Shizuku 重新授权和 X11 service 绑定。
- [x] ADB 双向文件传输复测：容器与 Android 文件往返 SHA256 一致；本机控制统一走 Shizuku shell/ADB 身份，不依赖外部 adb server。

## 发布前

- [ ] 创建并离线保存 release keystore，不提交仓库。
- [ ] 配置正式 release 签名。
- [ ] 继续减小 APK 体积；当前资产重复压缩和单 APK 分发不适合正式发布。
- [ ] 设置后续 versionCode 单调递增规则。
- [ ] 完成第三方许可证、来源和版本清单。
- [ ] 旧包 `com.doro.terminal.shell` 到新包的数据迁移或首次导入策略。
