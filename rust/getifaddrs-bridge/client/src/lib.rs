//! glibc `LD_PRELOAD` client for the Android getifaddrs bridge.

#![cfg(any(target_os = "linux", target_os = "android"))]

use std::collections::HashMap;
use std::ffi::c_void;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use linuxhub_getifaddrs_protocol as protocol;

const DEFAULT_SOCKET: &str = "/tmp/.getifaddrs-bridge";
const TIMEOUT: Duration = Duration::from_secs(2);

type RealGetifaddrs = unsafe extern "C" fn(*mut *mut libc::ifaddrs) -> libc::c_int;
type RealFreeifaddrs = unsafe extern "C" fn(*mut libc::ifaddrs);
static REAL_GETIFADDRS: OnceLock<Option<usize>> = OnceLock::new();
static REAL_FREEIFADDRS: OnceLock<Option<usize>> = OnceLock::new();
static LIST_ORIGINS: OnceLock<Mutex<HashMap<usize, ListOrigin>>> = OnceLock::new();

#[derive(Clone, Copy)]
enum ListOrigin {
    Bridge,
    Libc,
}

#[unsafe(no_mangle)]
/// Replace libc `getifaddrs` with the Android bridge and fall back to libc.
///
/// # Safety
///
/// `ifap` must be null or a valid writable pointer to a `*mut ifaddrs`.
pub unsafe extern "C" fn getifaddrs(ifap: *mut *mut libc::ifaddrs) -> libc::c_int {
    if ifap.is_null() {
        set_errno(libc::EINVAL);
        return -1;
    }
    unsafe { *ifap = std::ptr::null_mut() };

    if let Some(list) = bridge_getifaddrs() {
        remember_list(list, ListOrigin::Bridge);
        unsafe { *ifap = list };
        return 0;
    }

    let Some(real) = real_getifaddrs() else {
        set_errno(libc::ENOSYS);
        return -1;
    };
    let result = unsafe { real(ifap) };
    if result == 0 {
        remember_list(unsafe { *ifap }, ListOrigin::Libc);
    }
    result
}

#[unsafe(no_mangle)]
/// Free a list returned by this preload shim.
///
/// # Safety
///
/// `list` must be null or a list returned by this shim's `getifaddrs` and must
/// not have been freed already.
pub unsafe extern "C" fn freeifaddrs(list: *mut libc::ifaddrs) {
    if list.is_null() {
        return;
    }

    match take_list_origin(list) {
        Some(ListOrigin::Bridge) => unsafe { protocol::free_list(list) },
        Some(ListOrigin::Libc) | None => {
            if let Some(real) = real_freeifaddrs() {
                unsafe { real(list) };
            }
        }
    }
}

fn bridge_getifaddrs() -> Option<*mut libc::ifaddrs> {
    let socket_path = std::env::var_os("DORO_GETIFADDRS_SOCKET")
        .filter(|value| !value.is_empty())
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::path::PathBuf::from(DEFAULT_SOCKET));

    let mut stream = UnixStream::connect(socket_path).ok()?;
    stream.set_read_timeout(Some(TIMEOUT)).ok()?;
    stream.set_write_timeout(Some(TIMEOUT)).ok()?;

    let mut request = [0_u8; protocol::HEADER_SIZE];
    request[..4].copy_from_slice(protocol::MAGIC);
    stream.write_all(&request).ok()?;

    let mut header = [0_u8; protocol::HEADER_SIZE];
    stream.read_exact(&mut header).ok()?;
    let payload_len = protocol::payload_len(&header)?;
    let mut payload = vec![0_u8; payload_len];
    if payload_len != 0 {
        stream.read_exact(&mut payload).ok()?;
    }

    let list = unsafe { protocol::deserialize(&payload) };
    if list.is_null() && payload_len != 0 {
        None
    } else {
        Some(list)
    }
}

fn real_getifaddrs() -> Option<RealGetifaddrs> {
    let address = *REAL_GETIFADDRS.get_or_init(|| {
        let symbol = unsafe { libc::dlsym(libc::RTLD_NEXT, c"getifaddrs".as_ptr()) };
        (!symbol.is_null()).then_some(symbol as usize)
    });
    address.map(|address| unsafe { std::mem::transmute::<usize, RealGetifaddrs>(address) })
}

fn real_freeifaddrs() -> Option<RealFreeifaddrs> {
    let address = *REAL_FREEIFADDRS.get_or_init(|| {
        let symbol = unsafe { libc::dlsym(libc::RTLD_NEXT, c"freeifaddrs".as_ptr()) };
        (!symbol.is_null()).then_some(symbol as usize)
    });
    address.map(|address| unsafe { std::mem::transmute::<usize, RealFreeifaddrs>(address) })
}

fn remember_list(list: *mut libc::ifaddrs, origin: ListOrigin) {
    if list.is_null() {
        return;
    }
    let origins = LIST_ORIGINS.get_or_init(|| Mutex::new(HashMap::new()));
    match origins.lock() {
        Ok(mut origins) => {
            origins.insert(list as usize, origin);
        }
        Err(poisoned) => {
            poisoned.into_inner().insert(list as usize, origin);
        }
    }
}

fn take_list_origin(list: *mut libc::ifaddrs) -> Option<ListOrigin> {
    let origins = LIST_ORIGINS.get_or_init(|| Mutex::new(HashMap::new()));
    match origins.lock() {
        Ok(mut origins) => origins.remove(&(list as usize)),
        Err(poisoned) => poisoned.into_inner().remove(&(list as usize)),
    }
}

fn set_errno(value: libc::c_int) {
    unsafe { *libc::__errno_location() = value };
}

// Keep this type visible to the linker on older toolchains that discard unused
// C ABI imports during LTO.
#[allow(dead_code)]
fn _abi_guard(_: *mut c_void) {}
