# LinuxHub 2.0 Release Rules

## Mandatory Xlorie native build

- Release builds MUST enable the native Xlorie rebuild:

  ```sh
  sh ./gradlew :app:assembleRelease \
    -PrebuildX11Native=true \
    -x lintVitalRelease \
    --no-daemon
  ```

- NEVER package, install, publish, or hand out an APK built without
  `-PrebuildX11Native=true`. The default build path uses the stale
  `app/src/main/jniLibs-fallback/arm64-v8a/libXlorie.so`, which loses the
  `setContinuousFrames` JNI fix and the one-second idle SurfaceView keepalive.
- The signed release APK MUST be verified after building. At minimum check that:
  - the APK contains `lib/arm64-v8a/libXlorie.so`;
  - that library exports `setContinuousFrames`;
  - `assets/desktop/profile/doro-gpu-run.sh` contains `GSK_RENDERER=cairo` for
    the Qualcomm Zink/KGSL paths;
  - the app dex contains the `/proc/self/status` group-name repair.
- Keep the validated fallback library for emergency research only. It is not a
  release artifact.

## Cross-session memory

- Read `MEMORY.md` before touching release flow or the desktop stack.
- It holds the current published SHA-256, the mandatory build/verify checklist,
  and the pending backlog (kgsl A/B, dma-buf passthrough, input, audio, Wayland).
