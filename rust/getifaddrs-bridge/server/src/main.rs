//! Android getifaddrs bridge server.

#![cfg(any(target_os = "linux", target_os = "android"))]

use std::env;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileTypeExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use linuxhub_getifaddrs_protocol as protocol;

const TIMEOUT: Duration = Duration::from_secs(2);

fn main() -> ExitCode {
    let Some(socket_path) = env::args_os().nth(1).map(PathBuf::from) else {
        eprintln!("usage: getifaddrs_bridge_server <socket_path>");
        return ExitCode::from(2);
    };

    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
    }

    let _lock = match acquire_lock(&socket_path) {
        Ok(lock) => lock,
        Err(error) => {
            eprintln!("getifaddrs bridge lock failed: {error}");
            return ExitCode::FAILURE;
        }
    };

    if let Ok(metadata) = fs::symlink_metadata(&socket_path) {
        if !metadata.file_type().is_socket() {
            eprintln!("socket path is not a socket: {}", socket_path.display());
            return ExitCode::FAILURE;
        }
    }
    let _ = fs::remove_file(&socket_path);

    let listener = match UnixListener::bind(&socket_path) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("bind failed: {error}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(error) = fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600)) {
        eprintln!("chmod failed: {error}");
        return ExitCode::FAILURE;
    }

    for connection in listener.incoming() {
        match connection {
            Ok(stream) => handle_connection(stream),
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => continue,
        }
    }
    ExitCode::SUCCESS
}

fn handle_connection(mut stream: UnixStream) {
    let _ = stream.set_read_timeout(Some(TIMEOUT));
    let _ = stream.set_write_timeout(Some(TIMEOUT));

    let mut request = [0_u8; protocol::HEADER_SIZE];
    if stream.read_exact(&mut request).is_err() || !protocol::is_zero_request(&request) {
        return;
    }

    let mut list: *mut libc::ifaddrs = std::ptr::null_mut();
    if unsafe { libc::getifaddrs(&mut list) } != 0 {
        return;
    }
    let payload = unsafe { protocol::serialize(list) };
    unsafe { libc::freeifaddrs(list) };
    let Some(payload) = payload else {
        return;
    };
    let Some(header) = protocol::response_header(payload.len()) else {
        return;
    };
    if stream.write_all(&header).is_err() {
        return;
    }
    let _ = stream.write_all(&payload);
}

fn acquire_lock(socket_path: &Path) -> std::io::Result<std::fs::File> {
    let lock_path = env::var_os("DORO_GETIFADDRS_LOCK")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let mut path = socket_path.as_os_str().to_owned();
            path.push(".lock");
            PathBuf::from(path)
        });
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .mode(0o600)
        .open(lock_path)?;
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(lock)
}
