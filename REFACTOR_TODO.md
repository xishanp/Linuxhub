# LinuxHub 1.1.902 重构记录

更新时间：2026-10-18 02:00 CST
分支：`rebuild/1.1.9`
基线提交：`9106a0b8`

## 已确认决定

- 显示名：`LinuxHub`
- 版本名：`1.1.902`
- versionCode：`70`
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
- [x] 当前结果：503 个根包，解析后 583 个包，TAR 327 MB。
- [x] bootstrap TAR SHA256：`374f1223ab619a153fbacfaf8e30fea474785a5a153f3215be58565e2e6e8f4a`
- [x] 583 个包的 TAR 内容、清单和锁文件 SHA256 全部一致。
- [x] Firefox、Mesa 与 rootfs 资产改为 gzip 后仍沿用 `.tar` 文件名，运行时由 TarGzExtractor 自动识别；APK/分发体积显著下降。

### 安装流程

- [x] 初始化改为一次解压、一次 `dpkg --unpack`、一次 `dpkg --configure -a`。
- [x] 安装后使用 `dpkg -C` 做最终依赖审计，不再用 `|| true` 吞错。
- [x] 删除 `FactoryPackageRepairScript`、旧 vendor repair 路径和 factory-fix DEB 补丁。
- [x] Firefox 运行库由总包池提供；vendor 包只保留 Firefox 本体和桌面入口。
- [x] 保留星火商店、常用 CLI、蓝牙、网络管理、X11、XFCE、GPU 诊断工具和文件管理器。
- [x] 继续裁剪 Python/AI 等非基础预设，系统只保留必要运行库和用户明确保留的预设。

### 默认桌面

- [x] 默认分辨率 `1280x800`，固定 16:10。
- [x] `displayStretch=false`、`adjustResolution=false`。
- [x] 绿色壁纸、Tela Circle、WhiteSur GTK 作为首次默认值。
- [x] 用户已有桌面配置不因重启恢复成预设。
- [x] 主题资产安装移到初始化阶段，启动桌面时不再删除用户后来安装的 WhiteSur 图标包。

### 启动链路修复

- [x] 修复 Compose 初始化回调使用旧闭包状态的问题；创建用户后直接使用表单提交的用户名和密码，不再传入空用户名/密码。
- [x] 初始化页面文案改为 5–6 分钟目标时间，并保留真实进度和 watchdog。
- [x] 更新 `README.md`、`README_EN.md` 和 `OFFLINE_ASSETS.md`，移除旧版“预装开发环境”“30 分钟”“WhiteSur 图标”等失实说明。

## 构建验证

- `:app:testDebugUnitTest`：通过。
- `:app:assembleDebug`：通过。
- debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- clean rebuild 后 APK 为 623,902,840 bytes（约 595 MiB）。
- SHA256：`e959a8b567dab0400ff743471240768a34a089e70b4e08481284440f74c535a9`。
- APK 使用 Android debug certificate，未使用 release key。
- clean rebuild 后压缩总量 623,787,400 bytes，中央目录与签名开销 115,440 bytes，无重复 ZIP 条目。

## 待真机验证

- [ ] 安装新包 `com.linuxhub.app`；旧包数据不会自动继承。
- [ ] 断网从零初始化，确认 583 个包安装和 `dpkg -C` 审计通过。
- [ ] Firefox 可启动，`ldd` 无 `not found`。
- [ ] XFCE、X11、PulseAudio、输入法、蓝牙、网络管理正常。
- [ ] 16:10 无拉伸，壁纸和 Tela Circle 默认值正确。
- [ ] 用户修改默认桌面后重启不被覆盖。
- [ ] Turnip/Zink 硬件加速路径与重构前一致，并验证软件回退。
- [ ] 统计初始化耗时、rootfs 占用、桌面空闲内存和启动峰值。
- [ ] 改包名后的 Shizuku 重新授权和 X11 service 绑定。
- [ ] ADB 双向文件传输问题单独复测；这是主机/设备链路问题，不能由 APK 内补丁绕过。

## 发布前

- [ ] 创建并离线保存 release keystore，不提交仓库。
- [ ] 配置正式 release 签名。
- [ ] 继续减小 APK 体积；当前资产重复压缩和单 APK 分发不适合正式发布。
- [ ] 设置后续 versionCode 单调递增规则。
- [ ] 完成第三方许可证、来源和版本清单。
- [ ] 旧包 `com.doro.terminal.shell` 到新包的数据迁移或首次导入策略。
