# LinuxHub PRoot runtime

This directory vendors the Termux PRoot source used by LinuxHub 2.0 and provides
the reproducible Android AArch64 build that produces the shipped runtime.

## Source pin

- Repository: `https://github.com/termux/proot.git`
- Base release: `v5.1.107.87`
- Base commit: `87af48f58b752268cc4f93f251a9ca84e94c5655`
- Integrated commit: `d7033be7dd3b48b2d42a463a4e8547e40698c687`
- Integrated fix: `fake_id0: let the kernel do the real open for files & memfds when the requested read/write mode differs`
- Local build compatibility change: include `<string.h>` in
  `src/extension/ashmem_memfd/ashmem_memfd.c` for the NDK r29 build.

The exact upstream pin is also recorded in `upstream/SOURCE_COMMIT` and
`upstream/SOURCE_VERSION`.

## Build

The app's `libtalloc.so.2` and `libandroid-shmem.so` are the link dependencies.
The script uses the project NDK from `local.properties` by default.

```sh
tools/proot/build.sh
```

Override discovery when needed:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk \
ANDROID_API=28 \
NDK_VERSION=29.0.14206865 \
tools/proot/build.sh
```

The script builds and strips both binaries, then updates all four packaged copies:

- `app/src/main/jniLibs/arm64-v8a/libproot.so`
- `app/src/main/jniLibs/arm64-v8a/libproot-loader.so`
- `app/src/main/assets/runtime/arm64-v8a/proot`
- `app/src/main/assets/runtime/arm64-v8a/loader`

The build is done in a temporary directory; the vendored source remains clean.
Do not replace these files at runtime with upstream downloads.
