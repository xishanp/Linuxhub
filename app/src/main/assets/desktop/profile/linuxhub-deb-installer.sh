#!/bin/sh
set -u

pause_before_close() {
  if [ -t 0 ]; then
    printf '\n按回车关闭...'
    read -r _
  fi
}

fail() {
  printf '\n%s\n' "$1"
  pause_before_close
  exit "${2:-1}"
}

package=${1:-}
case "$package" in
  file://*) package=${package#file://} ;;
esac

if [ -z "$package" ]; then
  fail '没有选择 .deb 软件包。' 2
fi
if [ ! -f "$package" ]; then
  fail "找不到软件包：$package" 2
fi

case "$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')" in
  *.deb) ;;
  *) fail "只能安装 .deb 软件包：$package" 2 ;;
esac

package=$(readlink -f "$package" 2>/dev/null || printf '%s' "$package")
package_name=$(dpkg-deb -f "$package" Package 2>/dev/null || true)
package_version=$(dpkg-deb -f "$package" Version 2>/dev/null || true)
package_arch=$(dpkg-deb -f "$package" Architecture 2>/dev/null || true)

printf 'LinuxHub 软件包安装器\n'
printf '软件包：%s\n' "${package_name:-未知软件包}"
[ -n "$package_version" ] && printf '版本：%s\n' "$package_version"
[ -n "$package_arch" ] && printf '架构：%s\n' "$package_arch"
printf '文件：%s\n\n' "$package"

if [ -n "$package_arch" ] && [ "$package_arch" != arm64 ] && [ "$package_arch" != all ]; then
  fail "这个软件包是 $package_arch 架构，当前系统只能安装 arm64 或 all。" 1
fi
if ! command -v apt-get >/dev/null 2>&1; then
  fail '系统缺少 apt-get，无法安装软件包。' 1
fi

printf '正在验证管理员权限...\n'
if ! sudo -v; then
  fail '管理员验证失败，安装已取消。' 1
fi

user_home=$(getent passwd "$(id -u)" 2>/dev/null | cut -d: -f6)
[ -n "$user_home" ] || user_home=$HOME

printf '\n正在安装，请勿关闭窗口...\n\n'
sudo apt-get install -y "$package"
rc=$?

printf '\n'
if [ "$rc" -eq 0 ]; then
  # A package can add or replace launchers after the desktop session started.
  # Refresh every entry through the same system-wide compatibility path instead
  # of maintaining per-application patches.
  if [ -x /usr/local/bin/doro-refresh-launchers ]; then
    sudo /usr/local/bin/doro-refresh-launchers "$user_home" || {
      printf '警告：桌面启动器兼容处理失败，应用仍然已经安装。\n' >&2
    }
  fi
  printf '安装完成。\n'
else
  printf '安装失败，退出码：%s\n请查看上方 apt 输出。\n' "$rc"
fi
pause_before_close
exit "$rc"
