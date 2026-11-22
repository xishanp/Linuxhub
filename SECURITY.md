# Security Policy / 安全策略

## Supported version / 支持版本

Security fixes are provided for the latest source revision and the latest published release.

安全修复覆盖最新源码修订和最新正式发布版本。当前正式版本为 `Myth-Prometheus` / `201`。

## Reporting a vulnerability / 报告安全问题

请不要在公开 Issue 中披露尚未修复的安全问题。

Do not disclose an unpatched vulnerability in a public issue.

请通过 GitHub Security Advisory 私密报告，并提供：

- 受影响版本
- Android 与设备信息
- 复现步骤
- 影响范围
- 日志或概念验证
- 建议修复（如有）

Please use GitHub Security Advisories and include the affected version, device details, reproduction steps, impact, logs or proof of concept, and any suggested fix.

## Security boundaries / 安全边界

LinuxHub uses PRoot user-space isolation. PRoot is not a hardware virtual machine and must not be treated as a hostile-code security sandbox.

LinuxHub 使用 PRoot 用户空间隔离。PRoot 不是硬件虚拟机，不能当作运行恶意代码的安全沙箱。

用户主动绑定到 Linux 容器的 Android 共享存储、下载目录和个人文件会暴露给容器内程序。请只安装可信软件，并在运行不可信程序前移除不必要的目录绑定。

Android shared storage, downloads, and personal directories explicitly bound into the Linux container are visible to programs running there. Install trusted software and remove unnecessary bindings before running untrusted programs.

## Remote desktop / 远程桌面

- Remote desktop is started explicitly by the user and is not intended to be exposed directly to the public Internet.
- Access uses HTTPS with a self-signed certificate and a six-digit pairing code.
- The self-signed certificate protects transport encryption but is not a public CA identity. Do not treat it as proof that the server belongs to a particular person or device.
- Use USB forwarding or a trusted local network. Do not publish the listening port through router port forwarding or a public tunnel.
- Treat the pairing code as a password. Do not include it in screenshots, logs, bug reports, or chat messages.
- Stop the remote service when it is no longer needed.

- 远程桌面必须由用户主动开启，不应直接暴露到公网。
- 访问使用 HTTPS、自签名证书和六位配对码。
- 自签名证书提供传输加密，但不提供公共 CA 身份证明，不能单独证明服务器属于某个用户或设备。
- 优先使用 USB 端口转发或可信局域网，不要把监听端口通过路由器端口映射或公共隧道公开。
- 配对码等同于临时密码，不要写入截图、日志、Issue 或聊天记录。
- 不再使用时关闭远程服务。

## APK distribution and signing / APK 分发与签名

- Download APK files only from the official [GitHub Releases](https://github.com/xishanp/Linuxhub/releases) page.
- Verify the published SHA-256 before installation.
- The current release uses APK Signature Scheme v3.
- Do not install repackaged APKs from third parties.

- 只从官方 [GitHub Releases](https://github.com/xishanp/Linuxhub/releases) 下载 APK。
- 安装前核对发布页提供的 SHA-256。
- 当前版本使用 APK Signature Scheme v3。
- 不要安装第三方重新打包的 APK。

## Sensitive data / 敏感信息

提交日志前请删除用户名、文件路径、网络地址、令牌、配对码及个人信息。

Remove usernames, paths, network addresses, tokens, pairing codes, and personal information before publishing logs or screenshots.

## Downloaded packages / 下载的软件包

LinuxHub validates architecture, package identity, version, and the presence of system `apt-get` before launching the `.deb` installer. This reduces accidental package mistakes but does not make untrusted packages safe. Only install software from sources you trust.

LinuxHub 在启动 `.deb` 安装器前检查架构、包名、版本和系统 `apt-get`。这些检查只能减少误装，不能把不可信软件包变成安全软件。请只安装可信来源的软件。
