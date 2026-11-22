package com.doro.terminal.shell.linux

/**
 * Ubuntu 24.04 GNOME 46 X11 launch strategy.
 *
 * The official Ubuntu packages provide Mutter 46's X11 compositor backend. The
 * session therefore follows GNOME-Termux's proven principle directly: launch
 * GNOME Shell in a private D-Bus session against the existing Lorie X11
 * display, without GDM, Wayland, Weston, or gnome-session.
 */
internal object GnomeDesktopCompatibility {
    const val SESSION_NAME = "Ubuntu"
    const val SHELL_MODE = "linuxhub-ubuntu-min"
    internal const val SYSTEM_BUS_ADDRESS = "unix:path=/run/dbus/system_bus_socket"
    internal const val OVERLAY_ROOT = "/usr/local/share/linuxhub/gnome-shell-overlay"
    internal const val OVERLAY_RESOURCE_ROOT = "$OVERLAY_ROOT/org/gnome/shell"
    internal const val SYSTEM_BUS_CONFIG = "/usr/local/share/linuxhub/linuxhub-system-bus.conf"
    internal const val SESSION_BUS_CONFIG_TEMPLATE =
        "/usr/local/share/linuxhub/linuxhub-session-bus.conf.in"
    private const val SESSION_BUS_ABSTRACT_PREFIX = "linuxhub-session-bus-"

    fun systemServicesScript(): String =
        RustSessionControl.systemServicesScript() ?: legacySystemServicesScript()

    private fun legacySystemServicesScript(): String =
        "export DBUS_SYSTEM_BUS_ADDRESS=${quote(SYSTEM_BUS_ADDRESS)}; " +
            "mkdir -p /run/dbus; " +
            "doro_system_bus_ready=0; " +
            "if [ -S /run/dbus/system_bus_socket ] && " +
            "dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply " +
            "/ org.freedesktop.DBus.ListNames >/dev/null 2>&1; then " +
            "doro_system_bus_ready=1; " +
            "printf 'system_bus=reuse\\n' >>/tmp/doro-system-dbus.log; fi; " +
            "if [ \"\$doro_system_bus_ready\" -eq 0 ]; then " +
            "if [ -f /run/dbus/linuxhub-system-bus.pid ]; then " +
            "doro_old_system_pid=\$(cat /run/dbus/linuxhub-system-bus.pid 2>/dev/null || true); " +
            "if [ -n \"\$doro_old_system_pid\" ] && kill -0 \"\$doro_old_system_pid\" 2>/dev/null; then " +
            "kill -TERM \"\$doro_old_system_pid\" 2>/dev/null || true; " +
            "doro_stop_try=0; " +
            "while kill -0 \"\$doro_old_system_pid\" 2>/dev/null && " +
            "[ \"\$doro_stop_try\" -lt 20 ]; do " +
            "doro_stop_try=\$((doro_stop_try + 1)); sleep 0.1; done; " +
            "if kill -0 \"\$doro_old_system_pid\" 2>/dev/null; then " +
            "kill -KILL \"\$doro_old_system_pid\" 2>/dev/null || true; fi; fi; fi; " +
            "rm -f /run/dbus/system_bus_socket /run/dbus/pid /run/dbus/linuxhub-system-bus.pid; " +
            "test -f ${quote(SYSTEM_BUS_CONFIG)} " +
            "|| { printf 'system_bus=config_missing\\n' >>/tmp/doro-system-dbus.log; exit 1; }; " +
            "dbus-daemon --config-file=${quote(SYSTEM_BUS_CONFIG)} --fork --nopidfile --print-pid=1 " +
            "--address=\"\$DBUS_SYSTEM_BUS_ADDRESS\" 2>>/tmp/doro-system-dbus.log " +
            ">/run/dbus/linuxhub-system-bus.pid " +
            "|| { printf 'system_bus=start_failed\\n' >>/tmp/doro-system-dbus.log; exit 1; }; " +
            "doro_bus_try=0; " +
            "while [ \"\$doro_bus_try\" -lt 50 ]; do " +
            "if [ -S /run/dbus/system_bus_socket ] && " +
            "dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply " +
            "/ org.freedesktop.DBus.ListNames >/dev/null 2>&1; then break; fi; " +
            "doro_bus_try=\$((doro_bus_try + 1)); sleep 0.1; done; " +
            "if [ \"\$doro_bus_try\" -ge 50 ]; then " +
            "printf 'system_bus=unreachable attempts=%s\\n' \"\$doro_bus_try\" " +
            ">>/tmp/doro-system-dbus.log; exit 1; fi; " +
            "printf 'system_bus=ready pid=%s\\n' " +
            "\"\$(cat /run/dbus/linuxhub-system-bus.pid 2>/dev/null || true)\" " +
            ">>/tmp/doro-system-dbus.log; fi; " +
            "if ! dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply " +
            "/ org.freedesktop.DBus.NameHasOwner string:org.freedesktop.login1 2>/dev/null " +
            "| grep -q 'boolean true'; then " +
            "if [ -d /run/systemd/seats ] && ! rmdir /run/systemd/seats 2>/dev/null; then " +
            "printf 'logind=unavailable stale_seats=nonempty action=refuse_to_fake_logind\\n' " +
            ">>/tmp/doro-logind-capability.log; exit 1; fi; " +
            "printf 'logind=unavailable stale_seats=removed logind_features=disabled\\n' " +
            ">>/tmp/doro-logind-capability.log; fi; " +
            "if [ -x /usr/lib/polkit-1/polkitd ] && ! pgrep -x polkitd >/dev/null 2>&1; then " +
            "/usr/lib/polkit-1/polkitd --no-debug >/tmp/doro-polkitd.log 2>&1 & fi"

    fun sessionScript(display: String, username: String, gpuMode: GpuRenderMode): String =
        RustSessionControl.sessionScript(display, username, gpuMode)
            ?: legacySessionScript(display, username, gpuMode)

    private fun legacySessionScript(
        display: String,
        username: String,
        gpuMode: GpuRenderMode,
    ): String {
        val runtimeDir = "/tmp/runtime-$username"
        val sessionBusAbstract = "$SESSION_BUS_ABSTRACT_PREFIX$username"
        return "set -e; " +
            "export DISPLAY=${quote(display)}; " +
            "export doro_outer_display=${quote(display)}; " +
            "unset WAYLAND_DISPLAY SESSION_MANAGER; " +
            "if locale -a 2>/dev/null | grep -qi '^zh_CN\\.utf8$'; then " +
            "export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh; " +
            "else export LANG=C.UTF-8 LC_ALL=C.UTF-8; unset LANGUAGE; fi; " +
            "export XDG_RUNTIME_DIR=${quote(runtimeDir)}; " +
            "mkdir -p \"\$XDG_RUNTIME_DIR\"; chmod 700 \"\$XDG_RUNTIME_DIR\"; " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games; " +
            "export XDG_CURRENT_DESKTOP=ubuntu:GNOME XDG_SESSION_DESKTOP=ubuntu XDG_SESSION_TYPE=x11 DESKTOP_SESSION=ubuntu; " +
            "export GNOME_SHELL_SESSION_MODE=$SHELL_MODE; " +
            "export XDG_DATA_DIRS=/usr/local/share:/usr/share XDG_CONFIG_DIRS=/etc/xdg; " +
            "export GSETTINGS_SCHEMA_DIR=/usr/share/glib-2.0/schemas; " +
            "export GI_TYPELIB_PATH=/usr/lib/aarch64-linux-gnu/girepository-1.0:/usr/lib/girepository-1.0; " +
            "export GJS_PATH=/usr/share/gnome-shell LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/usr/lib; " +
            "export XCURSOR_THEME=Yaru XCURSOR_SIZE=24; " +
            "export DBUS_SYSTEM_BUS_ADDRESS=${quote(SYSTEM_BUS_ADDRESS)}; " +
            "export GDK_BACKEND=x11 CLUTTER_BACKEND=x11 MUTTER_DEBUG_FORCE_KMS_MODE=0 MESA_NO_ERROR=1; " +
            "export G_RESOURCE_OVERLAYS=${quote("/org/gnome/shell=$OVERLAY_RESOURCE_ROOT")}; " +
            GpuRuntimeEnvironment.gnomeSessionShell(gpuMode) +
            "export XMODIFIERS=@im=fcitx GTK_IM_MODULE=xim QT_IM_MODULE=xim; " +
            "unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID; " +
            "export doro_session_bus_abstract=${quote(sessionBusAbstract)}; " +
            "export DBUS_SESSION_BUS_ADDRESS=${quote("unix:abstract=$sessionBusAbstract")}; " +
            "doro_session_lock=\"\$XDG_RUNTIME_DIR/linuxhub-session-start.lock\"; " +
            "doro_lock_owned=0; " +
            "doro_release_session_lock() { " +
            "if [ \"\$doro_lock_owned\" -eq 1 ]; then " +
            "rm -f \"\$doro_session_lock/owner\"; " +
            "rmdir \"\$doro_session_lock\" 2>/dev/null || true; doro_lock_owned=0; fi; }; " +
            "doro_session_bus_reachable() { " +
            "dbus-send --bus=\"\$DBUS_SESSION_BUS_ADDRESS\" --dest=org.freedesktop.DBus " +
            "--type=method_call --print-reply / org.freedesktop.DBus.ListNames >/dev/null 2>&1; }; " +
            "doro_reuse_healthy_session() { " +
            "printf 'session=reuse pid=%s address=%s\\n' " +
            "\"\$(cat \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\" 2>/dev/null || true)\" " +
            "\"\$DBUS_SESSION_BUS_ADDRESS\" >>/tmp/doro-session-dbus.log; " +
            "doro_release_session_lock; trap - EXIT; " +
            "exec /bin/sh -c 'while sleep 3600; do :; done'; }; " +
            "doro_lock_try=0; " +
            "while ! mkdir \"\$doro_session_lock\" 2>/dev/null; do " +
            "if pgrep -x gnome-shell >/dev/null 2>&1 && doro_session_bus_reachable; then " +
            "doro_reuse_healthy_session; fi; " +
            "doro_lock_try=\$((doro_lock_try + 1)); " +
            "if [ \"\$doro_lock_try\" -ge 100 ]; then " +
            "doro_lock_owner=\$(cat \"\$doro_session_lock/owner\" 2>/dev/null || true); " +
            "if [ -z \"\$doro_lock_owner\" ] || ! kill -0 \"\$doro_lock_owner\" 2>/dev/null; then " +
            "rm -f \"\$doro_session_lock/owner\"; " +
            "rmdir \"\$doro_session_lock\" 2>/dev/null || true; " +
            "if mkdir \"\$doro_session_lock\" 2>/dev/null; then doro_lock_owned=1; break; fi; fi; " +
            "printf 'session_bus=lock_timeout lock=%s owner=%s\\n' " +
            "\"\$doro_session_lock\" \"\$doro_lock_owner\" >>/tmp/doro-session-dbus.log; exit 1; fi; " +
            "sleep 0.1; done; " +
            "if [ \"\$doro_lock_owned\" -eq 0 ]; then " +
            "doro_lock_owned=1; printf '%s\\n' \"\$\$\" > \"\$doro_session_lock/owner\"; fi; " +
            "trap 'doro_release_session_lock' EXIT; " +
            "if pgrep -x gnome-shell >/dev/null 2>&1 && doro_session_bus_reachable; then " +
            "doro_reuse_healthy_session; fi; " +
            "if doro_session_bus_reachable; then " +
            "printf 'session_bus=reuse pid=%s\\n' " +
            "\"\$(cat \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\" 2>/dev/null || true)\" " +
            ">>/tmp/doro-session-dbus.log; " +
            "else " +
            "if [ -f \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\" ]; then " +
            "doro_old_session_pid=\$(cat \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\" 2>/dev/null || true); " +
            "if [ -n \"\$doro_old_session_pid\" ] && kill -0 \"\$doro_old_session_pid\" 2>/dev/null; then " +
            "kill -TERM \"\$doro_old_session_pid\" 2>/dev/null || true; " +
            "doro_stop_try=0; " +
            "while kill -0 \"\$doro_old_session_pid\" 2>/dev/null && [ \"\$doro_stop_try\" -lt 20 ]; do " +
            "doro_stop_try=\$((doro_stop_try + 1)); sleep 0.1; done; " +
            "if kill -0 \"\$doro_old_session_pid\" 2>/dev/null; then " +
            "kill -KILL \"\$doro_old_session_pid\" 2>/dev/null || true; fi; fi; fi; " +
            "rm -f \"\$XDG_RUNTIME_DIR/linuxhub-session-bus\" " +
            "\"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\"; " +
            "test -f ${quote(SESSION_BUS_CONFIG_TEMPLATE)} " +
            "|| { printf 'session_bus=config_missing\\n' >>/tmp/doro-session-dbus.log; exit 1; }; " +
            "sed \"s|@SESSION_BUS_ABSTRACT@|\$doro_session_bus_abstract|g\" ${quote(SESSION_BUS_CONFIG_TEMPLATE)} " +
            "> \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.conf\" " +
            "|| { printf 'session_bus=config_render_failed\\n' >>/tmp/doro-session-dbus.log; exit 1; }; " +
            "doro_session_bus_pid=\$(dbus-daemon " +
            "--config-file=\"\$XDG_RUNTIME_DIR/linuxhub-session-bus.conf\" --fork --nopidfile " +
            "--print-pid=1 2>>/tmp/doro-session-dbus.log) " +
            "|| { printf 'session_bus=start_failed\\n' >>/tmp/doro-session-dbus.log; exit 1; }; " +
            "printf '%s\\n' \"\$doro_session_bus_pid\" > \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\"; " +
            "doro_session_bus_try=0; " +
            "while [ \"\$doro_session_bus_try\" -lt 50 ]; do " +
            "if doro_session_bus_reachable; then break; fi; " +
            "doro_session_bus_try=\$((doro_session_bus_try + 1)); sleep 0.1; done; " +
            "if [ \"\$doro_session_bus_try\" -ge 50 ]; then " +
            "printf 'session_bus=unreachable attempts=%s address=%s\\n' " +
            "\"\$doro_session_bus_try\" \"\$DBUS_SESSION_BUS_ADDRESS\" " +
            ">>/tmp/doro-session-dbus.log; exit 1; fi; fi; " +
            "export DBUS_SESSION_BUS_PID=\$(cat \"\$XDG_RUNTIME_DIR/linuxhub-session-bus.pid\" 2>/dev/null || true); " +
            "printf 'session_bus=ready pid=%s address=%s\\n' " +
            "\"\$DBUS_SESSION_BUS_PID\" \"\$DBUS_SESSION_BUS_ADDRESS\" " +
            ">>/tmp/doro-session-dbus.log; " +
            "doro_release_session_lock; trap - EXIT; " +
            "printf 'stage=exec-shell display=%s runtime=%s gpu=%s\\n' " +
            "\"\$DISPLAY\" \"\$XDG_RUNTIME_DIR\" \"\$doro_gpu_mode\" " +
            ">>/tmp/doro-startup-stage.log 2>/dev/null || true; " +
            "mkdir -p \"\$XDG_RUNTIME_DIR/pulse\"; chmod 700 \"\$XDG_RUNTIME_DIR/pulse\"; " +
            "doro_pulse_config=\"\$XDG_RUNTIME_DIR/pulseaudio.pa\"; " +
            "sed \"s|@PULSE_SOCKET@|\$XDG_RUNTIME_DIR/pulse/native|g\" " +
            "/usr/local/share/linuxhub/pulseaudio.pa > \"\$doro_pulse_config\" 2>/dev/null || true; " +
            "export PULSE_SERVER=\"unix:\$XDG_RUNTIME_DIR/pulse/native\"; " +
            "pkill -9 -u ${quote(username)} -x gnome-shell 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x gnome-session 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x Xwayland 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x pipewire 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x wireplumber 2>/dev/null || true; " +
            "pulseaudio --kill >/dev/null 2>&1 || true; " +
            "pkill -9 -u ${quote(username)} -x pulseaudio 2>/dev/null || true; " +
            "pkill -9 -x pulseaudio 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x gsd-media-keys 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x gsd-color 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x gsd-xsettings 2>/dev/null || true; " +
            "pkill -9 -u ${quote(username)} -x fcitx5 2>/dev/null || true; " +
            "rm -f \"\$XDG_RUNTIME_DIR/pulse/native\" \"\$XDG_RUNTIME_DIR/pulse/pid\"; " +
            "sleep 1; " +
            "if command -v fcitx5 >/dev/null 2>&1; then " +
            "fcitx5 -d --disable dbus >/tmp/doro-fcitx5.log 2>&1 || true; fi; " +
            "if [ -x /usr/libexec/gsd-color ]; then " +
            "/usr/libexec/gsd-color >/tmp/doro-gsd-color.log 2>&1 & fi; " +
            "if [ -x /usr/libexec/gsd-media-keys ]; then " +
            "/usr/libexec/gsd-media-keys >/tmp/doro-gsd-media-keys.log 2>&1 & fi; " +
            "if [ -x /usr/libexec/gsd-xsettings ] && ! pgrep -x gsd-xsettings >/dev/null 2>&1; then " +
            "(doro_xsettings_try=0; while [ \"\$doro_xsettings_try\" -lt 300 ]; do " +
            "if dbus-send --bus=\"\$DBUS_SESSION_BUS_ADDRESS\" --dest=org.freedesktop.DBus " +
            "--type=method_call --print-reply / org.freedesktop.DBus.NameHasOwner string:org.gnome.Shell " +
            "2>/dev/null | grep -q 'boolean true'; then " +
            "exec /usr/libexec/gsd-xsettings >/tmp/doro-gsd-xsettings.log 2>&1; fi; " +
            "doro_xsettings_try=\$((doro_xsettings_try + 1)); sleep 0.1; done) & fi; " +
            "pulseaudio --daemonize=yes --exit-idle-time=-1 " +
            "--log-target=file:/tmp/doro-pulseaudio.log --log-time=yes " +
            "-nF \"\$doro_pulse_config\" >/dev/null 2>&1 || true; " +
            "sleep 0.2; " +
            "exec /usr/bin/gnome-shell --x11 --replace " +
            "--mode=$SHELL_MODE >/tmp/doro-gnome-shell.log 2>&1"
    }

    fun stopScript(username: String): String =
        RustSessionControl.stopScript(username) ?: legacyStopScript(username)

    private fun legacyStopScript(username: String): String =
        "pkill -TERM -u ${quote(username)} -x gnome-shell 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x gnome-session 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x Xwayland 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x wireplumber 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x pipewire 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x pulseaudio 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x gsd-media-keys 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x gsd-color 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x gsd-xsettings 2>/dev/null || true; " +
            "pkill -TERM -u ${quote(username)} -x fcitx5 2>/dev/null || true; " +
            "if [ -f \"/tmp/runtime-$username/linuxhub-session-bus.pid\" ]; then " +
            "kill -TERM \"\$(cat \"/tmp/runtime-$username/linuxhub-session-bus.pid\")\" 2>/dev/null || true; fi; " +
            "sleep 1; " +
            "pkill -KILL -u ${quote(username)} -x gnome-shell 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x gnome-session 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x Xwayland 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x wireplumber 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x pipewire 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x pulseaudio 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x gsd-media-keys 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x gsd-color 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x gsd-xsettings 2>/dev/null || true; " +
            "pkill -KILL -u ${quote(username)} -x fcitx5 2>/dev/null || true; " +
            "rm -f \"/tmp/runtime-$username/linuxhub-session-bus\" " +
            "\"/tmp/runtime-$username/linuxhub-session-bus.conf\" " +
            "\"/tmp/runtime-$username/linuxhub-session-bus.pid\""

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
