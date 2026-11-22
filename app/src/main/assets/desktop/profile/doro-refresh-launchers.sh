#!/bin/sh
# Apply LinuxHub launch compatibility to every desktop entry.
#
# The caller may pass the container user's home directory. Running with sudo
# lets the helper update both system and user application directories.
set -u

if [ "$(id -u)" -ne 0 ]; then
  echo "doro-refresh-launchers 需要 root 权限" >&2
  exit 1
fi

if [ "$#" -gt 0 ]; then
  home=$1
else
  home=
fi
# A desktop process normally launches the installer as the container user.
# Some sudo configurations reset HOME to /root; translate it back to the
# invoking account so that user-owned launchers are refreshed too.
if [ -n "${SUDO_USER:-}" ] && [ "$(id -u)" -eq 0 ]; then
  sudo_home=$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)
  if [ -n "$sudo_home" ]; then
    home=$sudo_home
  fi
fi
home_root=${DORO_HOME_ROOT:-/home}
applications_dir=${DORO_APPLICATIONS_DIR:-/usr/share/applications}
wrapper=/usr/local/bin/doro-gpu-run
tmpdir=$(mktemp -d /tmp/doro-launchers.XXXXXX) || exit 1
trap 'rm -rf "$tmpdir"' EXIT HUP INT TERM

rewrite_file() {
  file=$1
  base=${file##*/}
  case "$base" in
    xfce4-session.desktop|xfwm4.desktop|xfdesktop.desktop|xfce4-panel.desktop|\
    panel-desktop-handler.desktop|panel-preferences.desktop|plank.desktop)
      return 0
      ;;
  esac

  tmp=$tmpdir/entry
  if [ "$base" = "wechat.desktop" ]; then
    awk -v prefix="/usr/local/bin/doro-wechat" -v main_exec="/usr/local/bin/doro-wechat %U" '
      BEGIN { section = "" }
      /^\[/ { section = $0; print; next }
      /^Exec=/ {
        command = substr($0, 6)
        if (section == "[Desktop Entry]") {
          print "Exec=" main_exec
        } else {
          sub(/^[^ \t]+[ \t]*/, "", command)
          if (command == "") print "Exec=" prefix
          else print "Exec=" prefix " " command
        }
        next
      }
      { print }
    ' "$file" > "$tmp"
  elif [ "$base" = "google-chrome.desktop" ] || [ "$base" = "com.google.Chrome.desktop" ]; then
    awk -v prefix="/usr/local/bin/doro-chrome" -v main_exec="/usr/local/bin/doro-chrome %U" '
      BEGIN { section = "" }
      /^\[/ { section = $0; print; next }
      /^Exec=/ {
        command = substr($0, 6)
        if (section == "[Desktop Entry]") {
          print "Exec=" main_exec
        } else {
          sub(/^[^ \t]+[ \t]*/, "", command)
          if (command == "") print "Exec=" prefix
          else print "Exec=" prefix " " command
        }
        next
      }
      /^Icon=/ && section == "[Desktop Entry]" { print "Icon=google-chrome"; next }
      { print }
    ' "$file" > "$tmp"
  else
    awk -v prefix="$wrapper " '
      /^Exec=/ {
        command = substr($0, 6)
        while (command ~ /^\/usr\/local\/bin\/doro-gpu-run[ \t]+/) {
          sub(/^\/usr\/local\/bin\/doro-gpu-run[ \t]+/, "", command)
        }
        if (command ~ /^\/usr\/local\/bin\/doro-(chrome|wechat)([ \t]|$)/) {
          print "Exec=" command
        } else {
          print "Exec=" prefix command
        }
        next
      }
      { print }
    ' "$file" > "$tmp"
  fi

  if ! cmp -s "$file" "$tmp"; then
    cat "$tmp" > "$file"
  fi
}

refresh_directory() {
  directory=$1
  [ -d "$directory" ] || return 0
  find "$directory" -maxdepth 1 -type f -name '*.desktop' -print |
    while IFS= read -r file; do
      rewrite_file "$file"
    done
}

refresh_directory "$applications_dir"
if [ -n "$home" ]; then
  refresh_directory "$home/Desktop"
  refresh_directory "$home/.local/share/applications"
else
  for user_home in "$home_root"/*; do
    refresh_directory "$user_home/Desktop"
    refresh_directory "$user_home/.local/share/applications"
  done
fi

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q "$applications_dir" \
    >/tmp/doro-refresh-launchers.log 2>&1 || :
fi
