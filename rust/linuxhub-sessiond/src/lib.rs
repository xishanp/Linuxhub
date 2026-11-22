//! Native session control-plane primitives for LinuxHub 2.0.
//!
//! This crate deliberately does not replace GNOME Shell, Mutter, GJS, or the
//! X11 server. It owns LinuxHub-specific launch sequencing, capability checks,
//! cleanup, and watchdog policy. The first supported baseline is Ubuntu 24.04
//! with the distro-provided GNOME Shell 46 / Mutter 46 X11 backend.

#[cfg(target_os = "android")]
mod jni_api;
mod scripts;

pub use scripts::{GpuMode, session_script, stop_script, system_services_script};

pub const DISTRIBUTION: &str = "Ubuntu 24.04 ARM64";
pub const GNOME_SHELL_PACKAGE: &str = "gnome-shell";
pub const MUTTER_ABI_PACKAGE: &str = "libmutter-14-0";
pub const SESSION_BUS_LAUNCHER: &str = "dbus-daemon";
pub const SHELL_BINARY: &str = "gnome-shell";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionPlan {
    pub display: String,
    pub username: String,
    pub runtime_dir: String,
    pub locale: String,
    pub software_rendering: bool,
}

impl SessionPlan {
    pub fn ubuntu_24_04_gnome46(display: &str, username: &str) -> Result<Self, &'static str> {
        if !valid_display(display) {
            return Err("display must look like :0 or :1");
        }
        if !valid_username(username) {
            return Err("username contains unsupported characters");
        }
        Ok(Self {
            display: display.to_owned(),
            username: username.to_owned(),
            runtime_dir: format!("/tmp/runtime-{username}"),
            locale: "zh_CN.UTF-8".to_owned(),
            software_rendering: true,
        })
    }

    pub fn environment(&self) -> Vec<(&'static str, String)> {
        vec![
            ("DISPLAY", self.display.clone()),
            ("XDG_RUNTIME_DIR", self.runtime_dir.clone()),
            ("XDG_CURRENT_DESKTOP", "ubuntu:GNOME".to_owned()),
            ("XDG_SESSION_DESKTOP", "ubuntu".to_owned()),
            ("XDG_SESSION_TYPE", "x11".to_owned()),
            ("DESKTOP_SESSION", "ubuntu".to_owned()),
            ("GNOME_SHELL_SESSION_MODE", "linuxhub-ubuntu-min".to_owned()),
            (
                "G_RESOURCE_OVERLAYS",
                "/org/gnome/shell=/usr/local/share/linuxhub/gnome-shell-overlay/org/gnome/shell"
                    .to_owned(),
            ),
            ("GDK_BACKEND", "x11".to_owned()),
            ("CLUTTER_BACKEND", "x11".to_owned()),
            ("LANG", self.locale.clone()),
            ("LC_ALL", self.locale.clone()),
            ("MESA_NO_ERROR", "1".to_owned()),
            ("MESA_LOADER_DRIVER_OVERRIDE", "swrast".to_owned()),
            ("GALLIUM_DRIVER", "llvmpipe".to_owned()),
            ("LIBGL_ALWAYS_SOFTWARE", "1".to_owned()),
        ]
    }

    /// Returns the LinuxHub-owned Shell launch script. The active Android path
    /// calls the same generator through JNI so its bus sequencing cannot drift.
    pub fn launch_script(&self) -> String {
        scripts::session_script(&self.display, &self.username, GpuMode::Software)
    }

    pub fn capability_probe_script(&self) -> String {
        let mut script = String::from("set +e\n");
        script.push_str("printf 'session_type=x11\\n'\n");
        script.push_str(
            "printf 'distribution='; . /etc/os-release; printf '%s\\n' \"$PRETTY_NAME\"\n",
        );
        script.push_str("dpkg-query -W -f='gnome-shell=%${Version}\\n' gnome-shell 2>/dev/null\n");
        script.push_str(
            "dpkg-query -W -f='libmutter-14-0=%${Version}\\n' libmutter-14-0 2>/dev/null\n",
        );
        script.push_str("command -v dbus-daemon dbus-send gnome-shell gjs xdpyinfo glxinfo >/dev/null; printf 'required_commands=%s\\n' \"$?\"\n");
        script.push_str("printf 'system_bus='; dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply / org.freedesktop.DBus.ListNames >/dev/null 2>&1; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'logind='; test -S /run/systemd/seats; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'polkit='; busctl --system status org.freedesktop.PolicyKit1 >/dev/null 2>&1; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'upower='; busctl --system status org.freedesktop.UPower >/dev/null 2>&1; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'network_manager='; busctl --system status org.freedesktop.NetworkManager >/dev/null 2>&1; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'portal='; busctl --user status org.freedesktop.portal.Desktop >/dev/null 2>&1; printf '%s\\n' \"$?\"\n");
        script.push_str("printf 'renderer='; glxinfo -B 2>/dev/null | sed -n 's/^[[:space:]]*OpenGL renderer string:[[:space:]]*//p' | head -1\n");
        script.push_str(
            "printf 'gnome_process='; pgrep -x gnome-shell >/dev/null; printf '%s\\n' \"$?\"\n",
        );
        script
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Capability {
    SessionDbus,
    SystemDbus,
    Logind,
    Polkit,
    UPower,
    NetworkManager,
    Portal,
    Llvmpipe,
    TouchInput,
}

impl Capability {
    /// Minimum capabilities for the first visible X11 bring-up gate.
    ///
    /// System D-Bus, touch behavior, logind, Polkit, and desktop services are
    /// probed separately and must not be reported as implemented merely
    /// because GNOME Shell can start without them.
    pub const REQUIRED_FOR_FIRST_DISPLAY: &'static [Self] = &[Self::SessionDbus, Self::Llvmpipe];

    pub const DEFERRED_DEPENDENCIES: &'static [Self] = &[
        Self::Logind,
        Self::Polkit,
        Self::UPower,
        Self::NetworkManager,
        Self::Portal,
    ];
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SupervisorPolicy {
    pub graceful_stop_ms: u64,
    pub max_restarts: u32,
    pub restart_window_ms: u64,
}

impl Default for SupervisorPolicy {
    fn default() -> Self {
        Self {
            graceful_stop_ms: 1_500,
            max_restarts: 3,
            restart_window_ms: 60_000,
        }
    }
}

fn valid_display(display: &str) -> bool {
    let Some(number) = display.strip_prefix(':') else {
        return false;
    };
    !number.is_empty()
        && number.chars().all(|c| c.is_ascii_digit() || c == '.')
        && !number.starts_with('.')
}

pub(crate) fn valid_username(username: &str) -> bool {
    !username.is_empty()
        && username.len() <= 32
        && username
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-'))
        && username
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ubuntu_24_profile_uses_direct_x11_shell_launch() {
        let plan = SessionPlan::ubuntu_24_04_gnome46(":0", "doro").unwrap();
        let script = plan.launch_script();

        assert!(
            script.contains("exec /usr/bin/gnome-shell --x11 --replace --mode=linuxhub-ubuntu-min")
        );
        assert!(script.contains("dbus-daemon"));
        assert!(script.contains("--config-file=\"$XDG_RUNTIME_DIR/linuxhub-session-bus.conf\""));
        assert!(
            script.contains("DBUS_SESSION_BUS_ADDRESS='unix:abstract=linuxhub-session-bus-doro'")
        );
        assert!(!script.contains("/usr/bin/dbus-run-session"));
        assert!(script.contains("XDG_SESSION_TYPE=x11"));
        assert!(script.contains("XDG_CURRENT_DESKTOP=ubuntu:GNOME"));
        assert!(script.contains("GDK_BACKEND=x11"));
        assert!(script.contains("CLUTTER_BACKEND=x11"));
        assert!(script.contains("GALLIUM_DRIVER=llvmpipe"));
        assert!(!script.contains("gnome-session --session"));
        assert!(!script.contains("startxfce4"));
        assert!(!script.contains("weston"));
        assert!(!script.contains("plank"));
    }

    #[test]
    fn runtime_directory_is_private() {
        let plan = SessionPlan::ubuntu_24_04_gnome46(":1", "tester").unwrap();
        assert_eq!(plan.runtime_dir, "/tmp/runtime-tester");
        assert!(
            plan.launch_script()
                .contains("chmod 700 \"$XDG_RUNTIME_DIR\"")
        );
    }

    #[test]
    fn invalid_inputs_are_rejected() {
        assert!(SessionPlan::ubuntu_24_04_gnome46("wayland-0", "doro").is_err());
        assert!(SessionPlan::ubuntu_24_04_gnome46(":0", "bad user").is_err());
    }

    #[test]
    fn deferred_services_are_not_claimed_as_required() {
        let required = Capability::REQUIRED_FOR_FIRST_DISPLAY;
        assert_eq!(required, &[Capability::SessionDbus, Capability::Llvmpipe]);
        assert!(!required.contains(&Capability::SystemDbus));
        assert!(!required.contains(&Capability::TouchInput));
        assert!(!required.contains(&Capability::Logind));
        assert!(Capability::DEFERRED_DEPENDENCIES.contains(&Capability::Logind));
        assert!(Capability::DEFERRED_DEPENDENCIES.contains(&Capability::Polkit));
    }

    #[test]
    fn probe_reports_capabilities_without_blocking_first_display() {
        let plan = SessionPlan::ubuntu_24_04_gnome46(":0", "doro").unwrap();
        let script = plan.capability_probe_script();

        assert!(script.contains("logind="));
        assert!(script.contains("polkit="));
        assert!(script.contains("renderer="));
        assert!(script.contains("libmutter-14-0"));
        assert!(!script.contains("exit 1"));
    }
}
