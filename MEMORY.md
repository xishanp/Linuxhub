# LinuxHub 跨会话记忆清单

> 给下一个会话（和我自己）看的：当前状态、发版铁律、待办清单、踩过的坑。
> 每次动发版或桌面栈之前，先读这一页。

## 一、当前状态（2026-11-22）

- 发布：GitHub Release tag `myth-prometheus`，**原地覆盖，不新开版本**
- 包名 `com.linuxhub.myth`，versionCode **201**，versionName `Myth-Prometheus`，显示名 `LinuxHub`
- 签名证书 SHA-256 `dc82aff0c06fdef4df5df54befbb79a0446a5ef95741c4f4ad18e0455b4fea10`（v3）
- 当前发布 APK SHA-256 `c4951876599b5a8a388170952039a724959f5c48004aec8f92e1de7379adc2b7`
  - 内容：平板大尺寸修复（`DesktopSizePolicy`）+ 原生 Xlorie 重建
- 回滚包：`artifacts/rollback/LinuxHub-Myth-Prometheus.apk`，SHA-256 `0aa2d258…`（无平板修复，Xlorie 正常）
- 源码分支：`main`、`codex/linuxhub-2.0`

## 二、发版铁律（吃过大亏）

1. 必须：
   ```sh
   sh ./gradlew :app:assembleRelease -PrebuildX11Native=true -x lintVitalRelease --no-daemon
   ```
   不带 `-PrebuildX11Native=true` 会打包 `app/src/main/jniLibs-fallback` 里的 **stale libXlorie.so**，
   丢掉 `setContinuousFrames` JNI 修复和 1 秒 idle SurfaceView keepalive。这个错误已经在 2026-11-22 发生过一次。
2. 签名前后都要核对四项：
   - `lib/arm64-v8a/libXlorie.so` 大小 **3547232**、SHA-256 前缀 `6e5b9865`
   - 该 .so 含字符串 `setContinuousFrames`
   - `assets/desktop/profile/doro-gpu-run.sh` 含 `GSK_RENDERER=cairo`
   - dex 里含 `/proc/self/status`（组名修复）
3. 覆盖发布：`gh release upload myth-prometheus <apk> <apk>.sha256 --clobber`，**不要动 tag**
4. 上传后用 `gh api repos/xishanp/Linuxhub/releases/tags/myth-prometheus` 核对 asset 的 `digest=`
5. 旧包不要删：回滚包保留在 `artifacts/rollback/`

## 三、待办清单（都能做，按优先级；用户说晚点弄）

- [ ] **1. kgsl 环境变量 A/B（最优先，1 小时）**
  `GALLIUM_DRIVER=freedreno` + `MESA_LOADER_DRIVER_OVERRIDE=kgsl` + `TURNIP_KMD=kgsl` + `FD_FORCE_KGSL=1`
  对比现在的 `GALLIUM_DRIVER=zink`。看三样：`chrome://gpu` 的 Vulkan / Video Encode、拖动帧率、CPU 占用。
  参考实现：`lfdevs/anland-termux` 的 `scripts/startgnome-anland.sh` → `enable_kgsl()`，按 `/dev/kgsl-3d0` 自动选路。
- [ ] **2. dma-buf 直通 Surface**（大工程，方向确定）
  让 Xlorie 渲染到我们分配的 dma-buf，再用 `ANativeWindow_queueBuffer` + render-done fence 直接交给 SurfaceFlinger；
  参考 `libdisplay_consumer/display_consumer.c`（`buf_info{stride,width,height,format,modifier,offset}`、8 个 buffer、5 秒超时降级 shm）。
  收益：拿到 native pixmap → VAAPI 硬件编码才有可能（也是 Chrome 里 Video Encode 软件化的根因）。
- [ ] **3. 输入模型**：相对位移 `pointer_motion{x,y,dx,dy}` + 独立 `pointer_axis{axis,value,discrete}` + pointer capture，
  配 4 个手感参数（scroll_speed / scroll_reverse / touchpad_scroll_threshold / gesture_scale）。对上“拖动卡顿”。
- [ ] **4. 刷新率回灌 pacing**：把 Android 当前刷新率回传给合成循环（对方协议里 `INPUT_TYPE_DISPLAY_REFRESH`）。
- [ ] **5. 音频独立通道**：音频走独立 socketpair，避免 PCM 把输入/剪贴板队头阻塞；AAudio 开流后读回设备真实 rate/channels 再回报生产端。
- [ ] **6. 剪贴板/文本输入/相机/麦克风**统一走事件通道，剪贴板用“最后发送内容”去重防回声。
- [ ] **7. 离线镜像 apt pin**：把 mutter / xwayland / mesa 用 `/etc/apt/preferences.d/` 钉住，防止系统升级把补丁冲掉。
- [ ] **8. `gnome-shell-ubuntu-extensions`** 进桌面镜像 → GNOME 托盘 + 桌面图标。风险极低。
- [ ] **9. HUD 浮层控件**（D-pad / 虚拟触控板 / 滚动条 / 按键组合 / 吸附几何）参考对方 `hud/` 目录。
- [ ] **10. 视频编码**：VAAPI 编码要 native GMB，X11 下拿不到 → 依赖第 2 项；或走 Termux:VA 式方案。
- [ ] **11. Wayland 双轨决策**：高通走 Wayland（Anland 式 dma-buf），天玑保留 X11。
  对方明确：天玑/Tensor/Exynos 只有 LLVMpipe，整体切换会让天玑倒退。切换前必须高通真机实测。
- [ ] **12. 平板 4K 本地桌面性能**：`displayResolutionMode` 默认 `native`，可见 Surface 绑上后 X11 根窗口会被拉到面板原生尺寸。
  要不要给本地桌面也加预算 clamp（牺牲清晰度换流畅）——未决定。

## 四、不要再犯的坑

- **不要删 worktree 的主仓库**：`LinuxHub-1.1.9-rebuild` 里放着所有 worktree 共享的 `.git`，删它等于删掉整个仓库元数据（2026-11-22 发生过，靠 GitHub 重建）。
- `docs/research/` 在 `.gitignore` 里：研究笔记不进仓库、不发布。
- 别拿视频/观感当性能结论，先在同一台机器上量数据。
- 天玑 vs 高通是两条腿：任何“只在测试机上爽”的方案都要问一句天玑怎么办。
