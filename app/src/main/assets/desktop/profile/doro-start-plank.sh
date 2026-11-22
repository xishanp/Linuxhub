#!/bin/sh
lock_dir="${XDG_RUNTIME_DIR:-/tmp}/linuxhub-plank.lock"
log_dir="$HOME/.cache/linuxhub"
log_file="$log_dir/plank.log"
mkdir -p "$log_dir"
exec >>"$log_file" 2>&1
printf '\n[%s] starting LinuxHub dock\n' "$(date '+%F %T')"

if mkdir "$lock_dir" 2>/dev/null; then
  printf '%s\n' "$$" > "$lock_dir/pid"
else
  running_pid="$(cat "$lock_dir/pid" 2>/dev/null || true)"
  if [ -n "$running_pid" ] && kill -0 "$running_pid" 2>/dev/null; then
    exit 0
  fi
  rm -rf "$lock_dir"
  mkdir "$lock_dir" 2>/dev/null || exit 0
  printf '%s\n' "$$" > "$lock_dir/pid"
fi

dock_root="$HOME/.config/plank/dock1"
launchers="$dock_root/launchers"
managed_items="['home.dockitem', 'terminal.dockitem', 'google-chrome.dockitem']"
schema_path='net.launchpad.plank.dock.settings:/net/launchpad/plank/docks/dock1/'

pkill -TERM -x plank 2>/dev/null || true
for _ in 1 2 3 4 5 6 7 8 9 10; do
  pgrep -x plank >/dev/null 2>&1 || break
  sleep 0.2
done
pkill -KILL -x plank 2>/dev/null || true

# Plank searches both the system and the user theme directories. Install the
# application-owned translucent gray theme in both so a stale system theme
# cannot win during the first session.
theme_source=/usr/share/plank/themes/LinuxHub-Dock/dock.theme
theme_target="$HOME/.local/share/plank/themes/LinuxHub-Dock/dock.theme"
mkdir -p "${theme_target%/*}"
if [ -f "$theme_source" ]; then
  cp -f "$theme_source" "$theme_target"
else
  printf 'missing theme source: %s\n' "$theme_source"
fi

if ! command -v gsettings >/dev/null 2>&1 && ! command -v dconf >/dev/null 2>&1; then
  printf 'gsettings/dconf unavailable; dock settings cannot be applied\n'
  exit 1
fi

apply_key() {
  key="$1"
  value="$2"
  if command -v gsettings >/dev/null 2>&1; then
    gsettings set "$schema_path" "$key" "$value"
  else
    dconf write "/net/launchpad/plank/docks/dock1/$key" "$value"
  fi
}

read_key() {
  if command -v gsettings >/dev/null 2>&1; then
    gsettings get "$schema_path" "$1"
  else
    dconf read "/net/launchpad/plank/docks/dock1/$1"
  fi
}

apply_key theme "'LinuxHub-Dock'"
apply_key position "'bottom'"
apply_key alignment "'center'"
apply_key items-alignment "'center'"
apply_key icon-size 64
apply_key hide-mode "'none'"
apply_key auto-pinning false
apply_key lock-items false
apply_key pinned-only false
apply_key show-dock-item false
apply_key zoom-enabled true
apply_key zoom-percent 200

printf 'theme-after-write=%s\n' "$(read_key theme)"

xfconf-query -c xfwm4 -p /general/use_compositing -s true 2>/dev/null || \
  xfconf-query -c xfwm4 -p /general/use_compositing -n -t bool -s true
xfconf-query -c xfce4-session -p /general/SaveOnExit -s false 2>/dev/null || \
  xfconf-query -c xfce4-session -p /general/SaveOnExit -n -t bool -s false

mkdir -p "$launchers"
rm -f "$launchers/app-grid.dockitem" "$launchers/firefox.dockitem" "$launchers/spark-store.dockitem"
printf '[PlankDockItemPreferences]\nLauncher=file:///usr/share/applications/google-chrome.desktop\n' > "$launchers/google-chrome.dockitem"
printf '[PlankDockItemPreferences]\nLauncher=file:///usr/share/applications/doro-terminal.desktop\n' > "$launchers/terminal.dockitem"
printf '[PlankDockItemPreferences]\nLauncher=file:///usr/share/applications/doro-home.desktop\n' > "$launchers/home.dockitem"

layout_marker="$HOME/.config/plank/doro-layout-v2"
if [ ! -f "$layout_marker" ]; then
  apply_key dock-items "$managed_items"
  mkdir -p "${layout_marker%/*}"
  touch "$layout_marker"
fi
printf 'dock-items=%s\n' "$(read_key dock-items)"

plank &
plank_pid=$!
printf '%s\n' "$plank_pid" > "$lock_dir/pid"
trap 'kill "$plank_pid" 2>/dev/null || true; rm -rf "$lock_dir"' EXIT HUP INT TERM
wait "$plank_pid"
