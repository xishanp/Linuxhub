#!/bin/sh
# The banner is cosmetic. A broken terminal renderer or PRoot syscall must
# never print a traceback into every new shell.
structure='Title:Separator:OS:Kernel:Uptime:Packages:Shell:Terminal:CPU:GPU:Memory:Colors'
tmp=$(mktemp /tmp/doro-fetch.XXXXXX 2>/dev/null) || exit 0
trap 'rm -f "$tmp"' EXIT HUP INT TERM

fallback() {
    user=$(id -un 2>/dev/null)
    [ -n "$user" ] || user=${USER:-user}
    printf '\033[1;32mLinuxHub\033[0m  Ubuntu 24.04  |  %s@localhost\n' "$user"
    printf '\033[2m容器已就绪 · 输入命令开始工作\033[0m\n'
}

if ! command -v fastfetch >/dev/null 2>&1; then
    fallback
    exit 0
fi

# Keep the Ubuntu mark even on phone-width terminals. A side-by-side layout
# squeezes both the logo and the information block, so render them separately.
if fastfetch --pipe false --logo ubuntu --logo-color-1 '#e95420' --structure Logo >"$tmp" 2>/dev/null; then
    cat "$tmp"
    printf '\033[38;5;196m------------------------------------------\033[0m\n'
else
    fallback
    exit 0
fi

if fastfetch --pipe false --logo none --structure "$structure" >"$tmp" 2>/dev/null; then
    cat "$tmp"
else
    fallback
fi
