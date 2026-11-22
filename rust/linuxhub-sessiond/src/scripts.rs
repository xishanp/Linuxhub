//! LinuxHub-owned GNOME session scripts.
//!
//! These scripts are intentionally kept in Rust so Android only performs JNI
//! lookup and process creation. The generated shell remains a compatibility
//! boundary for PRoot; this module does not claim to implement logind/systemd.

use std::fmt::Write as _;

pub const SYSTEM_BUS_ADDRESS: &str = "unix:path=/run/dbus/system_bus_socket";
pub const SESSION_BUS_CONFIG_TEMPLATE: &str =
    "/usr/local/share/linuxhub/linuxhub-session-bus.conf.in";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpuMode {
    Software,
    Zink,
    Kgsl,
}

impl GpuMode {
    pub fn parse(value: &str) -> Self {
        match value.trim() {
            "zink" => Self::Zink,
            "kgsl" => Self::Kgsl,
            _ => Self::Software,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::Software => "software",
            Self::Zink => "zink",
            Self::Kgsl => "kgsl",
        }
    }
}

pub fn system_services_script() -> String {
    let mut script = String::from("export DBUS_SYSTEM_BUS_ADDRESS=");
    script.push_str(&shell_quote(SYSTEM_BUS_ADDRESS));
    script.push_str(
        r#"; mkdir -p /run/dbus;
         doro_system_bus_ready=0;
         if [ -S /run/dbus/system_bus_socket ] &&
            dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply
            / org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
             doro_system_bus_ready=1;
             printf 'system_bus=reuse\n' >>/tmp/doro-system-dbus.log;
         fi;
         if [ "$doro_system_bus_ready" -eq 0 ]; then
             if [ -f /run/dbus/linuxhub-system-bus.pid ]; then
                 doro_old_system_pid=$(cat /run/dbus/linuxhub-system-bus.pid 2>/dev/null || true);
                 if [ -n "$doro_old_system_pid" ] && kill -0 "$doro_old_system_pid" 2>/dev/null; then
                     kill -TERM "$doro_old_system_pid" 2>/dev/null || true;
                     doro_stop_try=0;
                     while kill -0 "$doro_old_system_pid" 2>/dev/null && [ "$doro_stop_try" -lt 20 ]; do
                         doro_stop_try=$((doro_stop_try + 1)); sleep 0.1;
                     done;
                     if kill -0 "$doro_old_system_pid" 2>/dev/null; then
                         kill -KILL "$doro_old_system_pid" 2>/dev/null || true;
                     fi;
                 fi;
             fi;
             rm -f /run/dbus/system_bus_socket /run/dbus/pid /run/dbus/linuxhub-system-bus.pid;
             test -f /usr/local/share/linuxhub/linuxhub-system-bus.conf ||
                 { printf 'system_bus=config_missing\n' >>/tmp/doro-system-dbus.log; exit 1; };
             dbus-daemon --config-file=/usr/local/share/linuxhub/linuxhub-system-bus.conf
                 --fork --nopidfile --print-pid=1
                 --address="$DBUS_SYSTEM_BUS_ADDRESS" 2>>/tmp/doro-system-dbus.log >/run/dbus/linuxhub-system-bus.pid ||
                 { printf 'system_bus=start_failed\n' >>/tmp/doro-system-dbus.log; exit 1; };
             doro_bus_try=0;
             while [ "$doro_bus_try" -lt 50 ]; do
                 if [ -S /run/dbus/system_bus_socket ] &&
                    dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply
                    / org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
                     break;
                 fi;
                 doro_bus_try=$((doro_bus_try + 1)); sleep 0.1;
             done;
             if [ "$doro_bus_try" -ge 50 ]; then
                 printf 'system_bus=unreachable attempts=%s\n' "$doro_bus_try"
                     >>/tmp/doro-system-dbus.log; exit 1;
             fi;
             printf 'system_bus=ready pid=%s\n' "$(cat /run/dbus/linuxhub-system-bus.pid 2>/dev/null || true)"
                 >>/tmp/doro-system-dbus.log;
         fi;
         if ! dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply
            / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.login1 2>/dev/null |
            grep -q 'boolean true'; then
             if [ -d /run/systemd/seats ] && ! rmdir /run/systemd/seats 2>/dev/null; then
                 printf 'logind=unavailable stale_seats=nonempty action=refuse_to_fake_logind\n'
                     >>/tmp/doro-logind-capability.log; exit 1;
             fi;
             printf 'logind=unavailable stale_seats=removed logind_features=disabled\n'
                 >>/tmp/doro-logind-capability.log;
         fi;
         if [ -x /usr/lib/polkit-1/polkitd ]; then
             if ! pgrep -x polkitd >/dev/null 2>&1; then
                 /usr/lib/polkit-1/polkitd --no-debug >/tmp/doro-polkitd.log 2>&1 &
             fi;
         fi"#,
    );
    compact_shell(script)
}

pub fn session_script(display: &str, username: &str, gpu_mode: GpuMode) -> String {
    let runtime_dir = format!("/tmp/runtime-{username}");
    // A pathname socket can be unlinked by third-party desktop services while
    // the bus daemon is still healthy, leaving every GUI app unable to connect.
    // Bind a private abstract socket instead: its lifetime follows the daemon,
    // not cleanup code operating on /tmp.
    let session_bus_abstract = format!("linuxhub-session-bus-{username}");
    let session_bus_address = format!("unix:abstract={session_bus_abstract}");
    let mut script = String::from("set -e; ");
    script.push_str("export DISPLAY=");
    script.push_str(&shell_quote(display));
    script.push_str("; export doro_outer_display=");
    script.push_str(&shell_quote(display));
    script.push_str(
        "; unset WAYLAND_DISPLAY SESSION_MANAGER;          if locale -a 2>/dev/null | grep -qi '^zh_CN\\.utf8$'; then          export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh;          else export LANG=C.UTF-8 LC_ALL=C.UTF-8; unset LANGUAGE; fi;          export XDG_RUNTIME_DIR=",
    );
    script.push_str(&shell_quote(&runtime_dir));
    script.push_str(
        "; mkdir -p \"$XDG_RUNTIME_DIR\"; chmod 700 \"$XDG_RUNTIME_DIR\";          export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games;          export XDG_CURRENT_DESKTOP=ubuntu:GNOME XDG_SESSION_DESKTOP=ubuntu          XDG_SESSION_TYPE=x11 DESKTOP_SESSION=ubuntu GNOME_SHELL_SESSION_MODE=linuxhub-ubuntu-min;          export XDG_DATA_DIRS=/usr/local/share:/usr/share;          export XDG_CONFIG_DIRS=/etc/xdg;          export GSETTINGS_SCHEMA_DIR=/usr/share/glib-2.0/schemas;          export GI_TYPELIB_PATH=/usr/lib/aarch64-linux-gnu/girepository-1.0:/usr/lib/girepository-1.0;          export GJS_PATH=/usr/share/gnome-shell;          export LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/lib;          export XCURSOR_THEME=Yaru XCURSOR_SIZE=24;          export G_RESOURCE_OVERLAYS='/org/gnome/shell=/usr/local/share/linuxhub/gnome-shell-overlay/org/gnome/shell';          export DBUS_SYSTEM_BUS_ADDRESS=",
    );
    script.push_str(&shell_quote(SYSTEM_BUS_ADDRESS));
    script.push_str(
        "; export GDK_BACKEND=x11 CLUTTER_BACKEND=x11 MUTTER_DEBUG_FORCE_KMS_MODE=0 MESA_NO_ERROR=1; ",
    );
    script.push_str(&gpu_environment(gpu_mode));
    script.push_str("export doro_session_bus_abstract=");
    script.push_str(&shell_quote(&session_bus_abstract));
    script.push_str("; ");
    script.push_str(
        r#"export XMODIFIERS=@im=fcitx GTK_IM_MODULE=xim QT_IM_MODULE=xim;
         unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID;
         export DBUS_SESSION_BUS_ADDRESS="#,
    );
    script.push_str(&shell_quote(&session_bus_address));
    script.push_str(
        r#";
         doro_session_bus_reachable() {
             dbus-send --bus="$DBUS_SESSION_BUS_ADDRESS" --dest=org.freedesktop.DBus
                 --type=method_call --print-reply / org.freedesktop.DBus.ListNames
                 >/dev/null 2>&1;
         };
         doro_session_lock="$XDG_RUNTIME_DIR/linuxhub-session-start.lock";
         doro_lock_owned=0;
         doro_release_session_lock() {
             if [ "$doro_lock_owned" -eq 1 ]; then
                 rm -f "$doro_session_lock/owner";
                 rmdir "$doro_session_lock" 2>/dev/null || true;
                 doro_lock_owned=0;
             fi;
         };
         doro_reuse_healthy_session() {
             printf 'session=reuse pid=%s address=%s\n' \
                 "$(cat "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid" 2>/dev/null || true)" \
                 "$DBUS_SESSION_BUS_ADDRESS" >>/tmp/doro-session-dbus.log;
             doro_release_session_lock;
             trap - EXIT;
             exec /bin/sh -c 'while sleep 3600; do :; done';
         };
         doro_lock_try=0;
         while ! mkdir "$doro_session_lock" 2>/dev/null; do
             if pgrep -x gnome-shell >/dev/null 2>&1 && doro_session_bus_reachable; then
                 doro_reuse_healthy_session;
             fi;
             doro_lock_try=$((doro_lock_try + 1));
             if [ "$doro_lock_try" -ge 100 ]; then
                 doro_lock_owner=$(cat "$doro_session_lock/owner" 2>/dev/null || true);
                 if [ -z "$doro_lock_owner" ] || ! kill -0 "$doro_lock_owner" 2>/dev/null; then
                     rm -f "$doro_session_lock/owner";
                     rmdir "$doro_session_lock" 2>/dev/null || true;
                     if mkdir "$doro_session_lock" 2>/dev/null; then
                         doro_lock_owned=1;
                         break;
                     fi;
                 fi;
                 printf 'session_bus=lock_timeout lock=%s owner=%s\n' \
                     "$doro_session_lock" "$doro_lock_owner" >>/tmp/doro-session-dbus.log; exit 1;
             fi;
             sleep 0.1;
         done;
         if [ "$doro_lock_owned" -eq 0 ]; then
             doro_lock_owned=1;
             printf '%s\n' "$$" > "$doro_session_lock/owner";
         fi;
         trap 'doro_release_session_lock' EXIT;
         if pgrep -x gnome-shell >/dev/null 2>&1 && doro_session_bus_reachable; then
             doro_reuse_healthy_session;
         fi;
         if doro_session_bus_reachable; then
             printf 'session_bus=reuse pid=%s\n' \
                 "$(cat "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid" 2>/dev/null || true)"
                 >>/tmp/doro-session-dbus.log;
         else
             if [ -f "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid" ]; then
                 doro_old_session_pid=$(cat "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid" 2>/dev/null || true);
                 if [ -n "$doro_old_session_pid" ] && kill -0 "$doro_old_session_pid" 2>/dev/null; then
                     kill -TERM "$doro_old_session_pid" 2>/dev/null || true;
                     doro_stop_try=0;
                     while kill -0 "$doro_old_session_pid" 2>/dev/null && [ "$doro_stop_try" -lt 20 ]; do
                         doro_stop_try=$((doro_stop_try + 1)); sleep 0.1;
                     done;
                     if kill -0 "$doro_old_session_pid" 2>/dev/null; then
                         kill -KILL "$doro_old_session_pid" 2>/dev/null || true;
                     fi;
                 fi;
             fi;
             rm -f "$XDG_RUNTIME_DIR/linuxhub-session-bus"
                 "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid";
             test -f "#,
    );
    script.push_str(&shell_quote(SESSION_BUS_CONFIG_TEMPLATE));
    script.push_str(
        r#" || { printf 'session_bus=config_missing\n' >>/tmp/doro-session-dbus.log; exit 1; };
             sed "s|@SESSION_BUS_ABSTRACT@|$doro_session_bus_abstract|g" "#,
    );
    script.push_str(&shell_quote(SESSION_BUS_CONFIG_TEMPLATE));
    script.push_str(
        r#" > "$XDG_RUNTIME_DIR/linuxhub-session-bus.conf" ||
                 { printf 'session_bus=config_render_failed\n' >>/tmp/doro-session-dbus.log; exit 1; };
             doro_session_bus_pid=$(dbus-daemon
                 --config-file="$XDG_RUNTIME_DIR/linuxhub-session-bus.conf" --fork --nopidfile
                 --print-pid=1 2>>/tmp/doro-session-dbus.log) ||
                 { printf 'session_bus=start_failed\n' >>/tmp/doro-session-dbus.log; exit 1; };
             printf '%s\n' "$doro_session_bus_pid" > "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid";
             doro_session_bus_try=0;
             while [ "$doro_session_bus_try" -lt 50 ]; do
                 if doro_session_bus_reachable; then break; fi;
                 doro_session_bus_try=$((doro_session_bus_try + 1)); sleep 0.1;
             done;
             if [ "$doro_session_bus_try" -ge 50 ]; then
                 printf 'session_bus=unreachable attempts=%s address=%s\n'
                     "$doro_session_bus_try" "$DBUS_SESSION_BUS_ADDRESS"
                     >>/tmp/doro-session-dbus.log; exit 1;
             fi;
         fi;
         export DBUS_SESSION_BUS_PID=$(cat "$XDG_RUNTIME_DIR/linuxhub-session-bus.pid" 2>/dev/null || true);
         printf 'session_bus=ready pid=%s address=%s\n'
             "$DBUS_SESSION_BUS_PID" "$DBUS_SESSION_BUS_ADDRESS" >>/tmp/doro-session-dbus.log;
         doro_release_session_lock; trap - EXIT; "#,
    );
    let user = shell_quote(username);
    let _ = write!(
        script,
        "printf 'stage=exec-shell display=%s runtime=%s gpu=%s\\n' \
         \"$DISPLAY\" \"$XDG_RUNTIME_DIR\" \"$doro_gpu_mode\" \
         >>/tmp/doro-startup-stage.log 2>/dev/null || true; \
         mkdir -p \"$XDG_RUNTIME_DIR/pulse\"; chmod 700 \"$XDG_RUNTIME_DIR/pulse\"; \
         doro_pulse_config=\"$XDG_RUNTIME_DIR/pulseaudio.pa\"; \
         sed \"s|@PULSE_SOCKET@|$XDG_RUNTIME_DIR/pulse/native|g\" \
         /usr/local/share/linuxhub/pulseaudio.pa > \"$doro_pulse_config\" 2>/dev/null || true; \
         export PULSE_SERVER=\"unix:$XDG_RUNTIME_DIR/pulse/native\"; \
         pkill -9 -u {user} -x gnome-shell 2>/dev/null || true; \
         pkill -9 -u {user} -x gnome-session 2>/dev/null || true; \
         pkill -9 -u {user} -x Xwayland 2>/dev/null || true; \
         pkill -9 -u {user} -x pipewire 2>/dev/null || true; \
         pkill -9 -u {user} -x wireplumber 2>/dev/null || true; \
         pulseaudio --kill >/dev/null 2>&1 || true; \
         pkill -9 -u {user} -x pulseaudio 2>/dev/null || true; \
         pkill -9 -x pulseaudio 2>/dev/null || true; \
         pkill -9 -u {user} -x gsd-media-keys 2>/dev/null || true; \
         pkill -9 -u {user} -x gsd-color 2>/dev/null || true; \
         pkill -9 -u {user} -x gsd-xsettings 2>/dev/null || true; \
         pkill -9 -u {user} -x fcitx5 2>/dev/null || true; \
         rm -f \"$XDG_RUNTIME_DIR/pulse/native\" \"$XDG_RUNTIME_DIR/pulse/pid\"; \
         sleep 1; \
         if command -v fcitx5 >/dev/null 2>&1; then \
          fcitx5 -d --disable dbus >/tmp/doro-fcitx5.log 2>&1 || true; fi; \
         if [ -x /usr/libexec/gsd-color ]; then \
          /usr/libexec/gsd-color >/tmp/doro-gsd-color.log 2>&1 & fi; \
         if [ -x /usr/libexec/gsd-media-keys ]; then \
          /usr/libexec/gsd-media-keys >/tmp/doro-gsd-media-keys.log 2>&1 & fi; \
         if [ -x /usr/libexec/gsd-xsettings ] && ! pgrep -x gsd-xsettings >/dev/null 2>&1; then \
          (doro_xsettings_try=0; while [ \"$doro_xsettings_try\" -lt 300 ]; do \
           if dbus-send --bus=\"$DBUS_SESSION_BUS_ADDRESS\" --dest=org.freedesktop.DBus \
            --type=method_call --print-reply / org.freedesktop.DBus.NameHasOwner \
            string:org.gnome.Shell 2>/dev/null | grep -q 'boolean true'; then \
            exec /usr/libexec/gsd-xsettings >/tmp/doro-gsd-xsettings.log 2>&1; fi; \
           doro_xsettings_try=$((doro_xsettings_try + 1)); sleep 0.1; done) & fi; \
         pulseaudio --daemonize=yes --exit-idle-time=-1 \
         --log-target=file:/tmp/doro-pulseaudio.log --log-time=yes \
         -nF \"$doro_pulse_config\" >/dev/null 2>&1 || true; \
         sleep 0.2; \
         exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min \
         >/tmp/doro-gnome-shell.log 2>&1"
    );
    compact_shell(script)
}

pub fn stop_script(username: &str) -> String {
    let user = shell_quote(username);
    let runtime = shell_quote(&format!("/tmp/runtime-{username}"));
    format!(
        "pkill -TERM -u {user} -x gnome-shell 2>/dev/null || true; \
         pkill -TERM -u {user} -x gnome-session 2>/dev/null || true; \
         pkill -TERM -u {user} -x Xwayland 2>/dev/null || true; \
         pkill -TERM -u {user} -x wireplumber 2>/dev/null || true; \
         pkill -TERM -u {user} -x pipewire 2>/dev/null || true; \
         pkill -TERM -u {user} -x pulseaudio 2>/dev/null || true; \
         pkill -TERM -u {user} -x gsd-media-keys 2>/dev/null || true; \
         pkill -TERM -u {user} -x gsd-color 2>/dev/null || true; \
         pkill -TERM -u {user} -x gsd-xsettings 2>/dev/null || true; \
         pkill -TERM -u {user} -x fcitx5 2>/dev/null || true; \
         sleep 1; \
         pkill -KILL -u {user} -x gnome-shell 2>/dev/null || true; \
         pkill -KILL -u {user} -x gnome-session 2>/dev/null || true; \
         pkill -KILL -u {user} -x Xwayland 2>/dev/null || true; \
         pkill -KILL -u {user} -x wireplumber 2>/dev/null || true; \
         pkill -KILL -u {user} -x pipewire 2>/dev/null || true; \
         pkill -KILL -u {user} -x pulseaudio 2>/dev/null || true; \
         pkill -KILL -u {user} -x gsd-media-keys 2>/dev/null || true; \
         pkill -KILL -u {user} -x gsd-color 2>/dev/null || true; \
         pkill -KILL -u {user} -x gsd-xsettings 2>/dev/null || true; \
         pkill -KILL -u {user} -x fcitx5 2>/dev/null || true; \
         if [ -f {runtime}/linuxhub-session-bus.pid ]; then \
         kill -TERM \"$(cat {runtime}/linuxhub-session-bus.pid)\" 2>/dev/null || true; fi; \
         rm -f {runtime}/linuxhub-clipboard.pid {runtime}/linuxhub-session-bus \
         {runtime}/linuxhub-session-bus.conf {runtime}/linuxhub-session-bus.pid"
    )
}

fn gpu_environment(mode: GpuMode) -> String {
    // Mutter remains on llvmpipe: Zink can render client windows into their
    // pixmaps but fail to sample those textures in the Shell actor tree. The
    // selected mode is retained as a capability marker for doro-gpu-run.
    format!(
        "unset VK_ICD_FILENAMES VK_DRIVER_FILES MESA_VK_WSI_PRESENT_MODE \
         FD_KGSL_ENABLE_DMABUF LIBGL_ALWAYS_INDIRECT LIBGL_DRI3_DISABLE ZINK_DESCRIPTORS TU_DEBUG; \
         export doro_gpu_mode=software doro_gpu_capability={} \
         MESA_LOADER_DRIVER_OVERRIDE=swrast GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1; export GJS_DISABLE_JIT=1; ",
        mode.id()
    )
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

/// PRoot passes this text through `/bin/sh -c`. Compact generated files so a
/// formatting newline can never silently terminate a multi-line command.
fn compact_shell(script: String) -> String {
    script
        .replace("\\\n", " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::process::{Command, Stdio};

    #[test]
    fn scripts_are_valid_posix_shell() {
        for script in [
            system_services_script(),
            session_script(":0", "doro", GpuMode::Software),
            stop_script("doro"),
        ] {
            assert_shell_syntax(&script);
        }
    }

    #[test]
    fn session_keeps_official_x11_shell_launch_and_renderer_policy() {
        let software = session_script(":0", "doro", GpuMode::Software);
        assert!(
            software
                .contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min")
        );
        assert!(!software.contains("/usr/bin/dbus-run-session"));
        assert!(software.contains("MESA_LOADER_DRIVER_OVERRIDE=swrast"));
        assert!(software.contains("GALLIUM_DRIVER=llvmpipe"));
        assert!(software.contains("LIBGL_ALWAYS_SOFTWARE=1"));
        assert!(software.contains("GJS_DISABLE_JIT=1"));
        assert!(software.contains("XDG_CURRENT_DESKTOP=ubuntu:GNOME"));
        assert!(software.contains("G_RESOURCE_OVERLAYS='/org/gnome/shell=/usr/local/share/linuxhub/gnome-shell-overlay/org/gnome/shell'"));
        assert!(!software.contains("startxfce4"));
        assert!(!software.contains("weston"));
        assert!(!software.contains("plank"));

        let kgsl = session_script(":1", "tester", GpuMode::Kgsl);
        assert!(kgsl.contains("doro_gpu_mode=software doro_gpu_capability=kgsl"));
        assert!(kgsl.contains("MESA_LOADER_DRIVER_OVERRIDE=swrast"));
        assert!(!kgsl.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"));
        assert!(!kgsl.contains("VK_ICD_FILENAMES="));

        let zink = session_script(":1", "tester", GpuMode::Zink);
        assert!(zink.contains("doro_gpu_mode=software doro_gpu_capability=zink"));
        assert!(zink.contains("MESA_LOADER_DRIVER_OVERRIDE=swrast"));
        assert!(zink.contains("GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1"));
        assert!(!zink.contains("MESA_LOADER_DRIVER_OVERRIDE=zink"));
        assert!(!zink.contains("VK_ICD_FILENAMES="));
    }

    #[test]
    fn session_exports_private_system_bus_and_runtime_dir() {
        let script = session_script(":0", "doro", GpuMode::Software);
        assert!(script.contains("DBUS_SYSTEM_BUS_ADDRESS='unix:path=/run/dbus/system_bus_socket'"));
        assert!(script.contains("XDG_RUNTIME_DIR='/tmp/runtime-doro'"));
        assert!(
            script.contains("DBUS_SESSION_BUS_ADDRESS='unix:abstract=linuxhub-session-bus-doro'")
        );
        assert!(script.contains("doro_session_bus_abstract='linuxhub-session-bus-doro'"));
        assert!(script.contains("s|@SESSION_BUS_ABSTRACT@|$doro_session_bus_abstract|g"));
        assert!(script.contains("dbus-daemon"));
        assert!(script.contains("--config-file=\"$XDG_RUNTIME_DIR/linuxhub-session-bus.conf\""));
        assert!(script.contains("dbus-send --bus=\"$DBUS_SESSION_BUS_ADDRESS\""));
        assert!(script.contains("linuxhub-session-bus.conf.in"));
        assert!(
            script.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min")
        );
        assert!(!script.contains("/usr/bin/dbus-run-session"));
        assert!(script.contains("stage=exec-shell"));
        assert!(script.contains("linuxhub-session-start.lock"));
        assert!(script.contains("session=reuse"));
        assert!(script.contains("session_bus=reuse"));
        assert!(script.contains("kill -TERM \"$doro_old_session_pid\""));
        assert!(!script.contains("pkill -9 -x dbus-daemon"));
    }

    #[test]
    fn session_starts_audio_helper() {
        let script = session_script(":0", "doro", GpuMode::Software);
        assert!(script.contains("pulseaudio"));
        assert!(script.contains("pulseaudio.pa"));
        assert!(script.contains("doro-pulseaudio.log"));
        assert!(script.contains("PULSE_SERVER=\"unix:$XDG_RUNTIME_DIR/pulse/native\""));
        assert!(script.contains("fcitx5 -d --disable dbus >/tmp/doro-fcitx5.log"));
        assert!(script.contains("GTK_IM_MODULE=xim QT_IM_MODULE=xim"));
        assert!(script.contains("/usr/libexec/gsd-media-keys >/tmp/doro-gsd-media-keys.log"));
        assert!(script.contains("/usr/libexec/gsd-color >/tmp/doro-gsd-color.log"));
        assert!(script.contains("/usr/libexec/gsd-xsettings >/tmp/doro-gsd-xsettings.log"));
        assert!(script.contains("string:org.gnome.Shell"));
        assert!(!script.contains("/usr/libexec/gsd-power"));
        assert!(!script.contains("/usr/libexec/gsd-rfkill"));
    }

    #[test]
    fn system_services_do_not_advertise_logind_without_a_dbus_owner() {
        let script = system_services_script();
        assert!(script.contains("org.freedesktop.DBus.NameHasOwner string:org.freedesktop.login1"));
        assert!(script.contains("grep -q 'boolean true'"));
        assert!(script.contains("rmdir /run/systemd/seats"));
        assert!(script.contains("/usr/local/share/linuxhub/linuxhub-system-bus.conf"));
        assert!(script.contains("system_bus=config_missing"));
        assert!(!script.contains("dbus-daemon-launch-helper"));
        assert!(!script.contains("s#<user>messagebus</user>##"));
        assert!(script.contains("system_bus=unreachable"));
        assert!(script.contains("action=refuse_to_fake_logind"));
        assert!(script.contains("logind_features=disabled"));
        assert!(script.contains("system_bus=reuse"));
        assert!(script.contains("/run/dbus/linuxhub-system-bus.pid"));
        assert!(!script.contains("pkill -9 -x dbus-daemon"));
    }

    #[test]
    fn shell_runs_in_the_foreground_without_background_helpers() {
        let script = session_script(":0", "doro", GpuMode::Software);
        assert!(
            script.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min")
        );
        assert!(!script.contains("doro_shell_pid"));
        assert!(!script.contains("gnome-shell --x11 --replace &"));
        assert!(!script.contains("launch.fifo"));
        assert!(!script.contains("clipboard-daemon"));
    }

    #[test]
    fn stop_cleans_up_owned_session_helpers() {
        let script = stop_script("doro");
        assert!(!script.contains("lotus"));
        assert!(script.contains("-x pulseaudio"));
        assert!(script.contains("-x gsd-media-keys"));
        assert!(script.contains("-x fcitx5"));
        assert!(script.contains("rm -f '/tmp/runtime-doro'/linuxhub-clipboard.pid"));
        assert!(script.contains("'/tmp/runtime-doro'/linuxhub-session-bus.pid"));
    }

    fn assert_shell_syntax(script: &str) {
        let mut child = Command::new("sh")
            .arg("-n")
            .stdin(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .expect("start sh -n");
        child
            .stdin
            .take()
            .expect("stdin")
            .write_all(script.as_bytes())
            .expect("write script");
        let output = child.wait_with_output().expect("wait for sh -n");
        assert!(
            output.status.success(),
            "shell syntax failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
