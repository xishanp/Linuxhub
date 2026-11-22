#!/bin/sh
# Opt one application process tree into the selected hardware backend.
# Qualcomm uses Zink/Turnip; MediaTek uses virpipe/VirGL. The GNOME session
# itself stays on llvmpipe so a failed vendor path cannot black-screen Mutter.
#
# GNOME Shell needs a private LD_LIBRARY_PATH to load Mutter/GJS from the
# multiarch directory. That path must not leak into application processes:
# LibreOffice otherwise resolves libuno from /usr/lib/aarch64-linux-gnu and
# looks for its URE bootstrap at /usr/lib/aarch64-linux-gnu/unorc, where no
# unorc exists. Application launch paths must use the system loader config.
unset LD_LIBRARY_PATH

gpu_available=false
gpu_mode=$(cat /etc/doro-gpu-mode 2>/dev/null)
if [ "$(cat /etc/doro-gpu-enabled 2>/dev/null)" = "1" ] &&
   { [ "$gpu_mode" = "zink" ] || [ "$gpu_mode" = "kgsl" ] || [ "$gpu_mode" = "virgl" ]; }; then
    gpu_available=true
fi

requested=${DORO_GPU_RENDERER:-auto}
case "$requested" in
    zink|kgsl|virgl|software) ;;
    *) requested=auto ;;
esac
[ "$gpu_available" = "true" ] || requested=software

if [ "$#" -lt 1 ]; then
    echo "用法: doro-gpu-run <命令> [参数...]" >&2
    exit 2
fi

cache_dir=${XDG_RUNTIME_DIR:-/tmp}/linuxhub
cache_file=$cache_dir/gpu-app-renderer
if [ "$requested" = "auto" ]; then
    cached=$(cat "$cache_file" 2>/dev/null)
    case "$cached" in
        zink|kgsl|virgl) requested=$cached ;;
        *) requested="" ;;
    esac
fi

if [ -z "$requested" ] && [ "$gpu_mode" = "virgl" ]; then
    requested=virgl
fi

if [ -z "$requested" ] && [ -n "${DISPLAY:-}" ] && command -v glxinfo >/dev/null 2>&1; then
    renderer=$(
        env -u LIBGL_ALWAYS_SOFTWARE MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink \
            VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json \
            VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json \
            FD_KGSL_ENABLE_DMABUF=1 \
            timeout 6 glxinfo -B 2>/dev/null |
            sed -n 's/^OpenGL renderer string: //p' |
            head -n 1
    )
    case "$renderer" in
        *zink*|*Zink*) requested=zink ;;
    esac
fi

if [ -z "$requested" ] && [ "$gpu_available" = "true" ]; then
    renderer=$(
        env -u LIBGL_ALWAYS_SOFTWARE MESA_LOADER_DRIVER_OVERRIDE=kgsl GALLIUM_DRIVER=kgsl \
            timeout 6 glxinfo -B 2>/dev/null |
            sed -n 's/^OpenGL renderer string: //p' |
            head -n 1
    )
    case "$renderer" in
        *Adreno*|*freedreno*) requested=kgsl ;;
    esac
fi

if [ -z "$requested" ] && [ "$gpu_mode" = "virgl" ]; then
    requested=virgl
fi

[ -n "$requested" ] || requested=software
if [ "$requested" != "software" ]; then
    mkdir -p "$cache_dir" 2>/dev/null || true
    printf '%s\n' "$requested" > "$cache_file.tmp.$$" 2>/dev/null &&
        mv -f "$cache_file.tmp.$$" "$cache_file" 2>/dev/null || true
fi

case "$requested" in
    zink)
        export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export FD_KGSL_ENABLE_DMABUF=1
        export MESA_VK_WSI_PRESENT_MODE=mailbox MESA_SHADER_CACHE_DISABLE=false
        export MESA_SHADER_CACHE_MAX_SIZE=512M
        # GTK4's NGL renderer corrupts tab strips and damaged regions when its GL
        # context runs through Zink/Turnip on affected Adreno drivers. Keep the
        # application process on the hardware driver, but let GTK paint through
        # Cairo so plain desktop widgets do not hit that broken GL presentation path.
        export GSK_RENDERER=cairo
        unset LIBGL_ALWAYS_SOFTWARE LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE
        ;;
    kgsl)
        export MESA_LOADER_DRIVER_OVERRIDE=kgsl GALLIUM_DRIVER=kgsl
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export FD_KGSL_ENABLE_DMABUF=1
        export MESA_VK_WSI_PRESENT_MODE=mailbox MESA_SHADER_CACHE_DISABLE=false
        export MESA_SHADER_CACHE_MAX_SIZE=512M
        export GSK_RENDERER=cairo
        unset LIBGL_ALWAYS_SOFTWARE LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE
        ;;
    virgl)
        export GALLIUM_DRIVER=virpipe VTEST_SOCKET=/tmp/.virgl_test
        unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES
        unset FD_KGSL_ENABLE_DMABUF LIBGL_ALWAYS_SOFTWARE LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE
        ;;
    *)
        unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES
        unset MESA_VK_WSI_PRESENT_MODE FD_KGSL_ENABLE_DMABUF VTEST_SOCKET
        unset LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE GSK_RENDERER
        export GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1
        ;;
esac

export DORO_GPU_RUNTIME=1
export DORO_GPU_RENDERER=$requested
command=$1
if [ "$#" -gt 0 ]; then
    shift
fi
if [ "${command##*/}" = "xfce4-terminal" ]; then
    case " $* " in
        *" --hide-menubar "*) ;;
        *) set -- --hide-menubar "$@" ;;
    esac
fi
printf 'LinuxHub GPU app: renderer=%s command=%s\n' "$requested" "$command" \
    >> /tmp/doro-gpu-runtime.log 2>/dev/null || true
exec "$command" "$@"
