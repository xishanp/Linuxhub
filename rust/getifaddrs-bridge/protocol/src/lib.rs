//! Framing and interface serialization shared by the Android bridge server and
//! the Ubuntu glibc LD_PRELOAD client.

#![cfg(any(target_os = "linux", target_os = "android"))]

use std::ffi::CStr;
use std::mem::size_of;
use std::slice;

pub const MAGIC: &[u8; 4] = b"TIF1";
pub const HEADER_SIZE: usize = 8;
pub const MAX_PAYLOAD: usize = 256 * 1024;
const MAX_NAME: usize = 255;
const IF_NAME_LIMIT: usize = 16;

pub fn response_header(payload_len: usize) -> Option<[u8; HEADER_SIZE]> {
    if payload_len > MAX_PAYLOAD {
        return None;
    }
    let mut header = [0_u8; HEADER_SIZE];
    header[..4].copy_from_slice(MAGIC);
    header[4..].copy_from_slice(&(payload_len as u32).to_be_bytes());
    Some(header)
}

pub fn validate_header(header: &[u8; HEADER_SIZE], expected_len: Option<usize>) -> bool {
    let Some(actual_len) = payload_len(header) else {
        return false;
    };
    expected_len.is_none_or(|expected| actual_len == expected)
}

pub fn payload_len(header: &[u8; HEADER_SIZE]) -> Option<usize> {
    if header[..4] != *MAGIC {
        return None;
    }
    let length = u32::from_be_bytes(header[4..8].try_into().ok()?) as usize;
    (length <= MAX_PAYLOAD).then_some(length)
}

pub fn is_zero_request(header: &[u8; HEADER_SIZE]) -> bool {
    validate_header(header, Some(0))
}

/// Serialize the linked `ifaddrs` list returned by libc.
///
/// # Safety
///
/// `list` must be null or point to a valid libc `ifaddrs` list for the duration
/// of this call. Every entry reachable through `ifa_next` must obey the same
/// contract, and address pointers must refer to readable `sockaddr` data.
pub unsafe fn serialize(list: *mut libc::ifaddrs) -> Option<Vec<u8>> {
    let mut output = Vec::with_capacity(4096);
    let mut cursor = list;

    while !cursor.is_null() {
        let entry = unsafe { &*cursor };

        let name = if entry.ifa_name.is_null() {
            &[][..]
        } else {
            let value = unsafe { CStr::from_ptr(entry.ifa_name) }.to_bytes();
            &value[..value.len().min(IF_NAME_LIMIT)]
        };
        append_u32(&mut output, name.len() as u32)?;
        append_bytes(&mut output, name)?;
        unsafe { append_field(&mut output, entry.ifa_addr)? };
        unsafe { append_field(&mut output, entry.ifa_netmask)? };

        let other = if entry.ifa_flags & libc::IFF_BROADCAST as libc::c_uint != 0
            || entry.ifa_flags & libc::IFF_POINTOPOINT as libc::c_uint != 0
        {
            entry.ifa_ifu
        } else {
            std::ptr::null_mut()
        };
        unsafe { append_field(&mut output, other)? };
        append_u32(&mut output, 0)?;
        append_u32(&mut output, entry.ifa_flags)?;

        if output.len() > MAX_PAYLOAD {
            return None;
        }
        cursor = entry.ifa_next;
    }

    Some(output)
}

/// Rebuild a libc-compatible `ifaddrs` list allocated with malloc/calloc.
///
/// # Safety
///
/// The returned list, if non-null, must be freed with [`free_list`]. It must
/// not be passed to libc `freeifaddrs`, because its ownership model is local to
/// this crate.
pub unsafe fn deserialize(payload: &[u8]) -> *mut libc::ifaddrs {
    let mut cursor = Cursor::new(payload);
    let mut head: *mut libc::ifaddrs = std::ptr::null_mut();
    let mut tail: *mut *mut libc::ifaddrs = &mut head;

    while cursor.remaining() > 0 {
        let Some(name) = cursor.field() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };
        if name.len() > MAX_NAME {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        }
        let Some(addr) = cursor.field() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };
        let Some(mask) = cursor.field() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };
        let Some(other) = cursor.field() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };
        let Some(_ifa_data) = cursor.field() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };
        let Some(flags) = cursor.u32() else {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        };

        let entry = unsafe { libc::calloc(1, size_of::<libc::ifaddrs>()) } as *mut libc::ifaddrs;
        if entry.is_null() {
            unsafe { free_list(head) };
            return std::ptr::null_mut();
        }

        let name_copy = unsafe { libc::malloc(name.len() + 1) } as *mut u8;
        if name_copy.is_null() {
            unsafe {
                libc::free(entry as *mut libc::c_void);
                free_list(head);
            }
            return std::ptr::null_mut();
        }
        unsafe {
            std::ptr::copy_nonoverlapping(name.as_ptr(), name_copy, name.len());
            *name_copy.add(name.len()) = 0;
            (*entry).ifa_name = name_copy as *mut libc::c_char;
            (*entry).ifa_flags = flags;
        }

        let addr_copy = unsafe { copy_field(addr) };
        let mask_copy = unsafe { copy_field(mask) };
        let other_copy = unsafe { copy_field(other) };
        if (addr_copy.is_null() && !addr.is_empty())
            || (mask_copy.is_null() && !mask.is_empty())
            || (other_copy.is_null() && !other.is_empty())
        {
            unsafe {
                libc::free(name_copy as *mut libc::c_void);
                libc::free(addr_copy as *mut libc::c_void);
                libc::free(mask_copy as *mut libc::c_void);
                libc::free(other_copy as *mut libc::c_void);
                libc::free(entry as *mut libc::c_void);
                free_list(head);
            }
            return std::ptr::null_mut();
        }

        unsafe {
            (*entry).ifa_addr = addr_copy;
            (*entry).ifa_netmask = mask_copy;
            (*entry).ifa_ifu = other_copy;
            (*entry).ifa_data = std::ptr::null_mut();
            *tail = entry;
            tail = &mut (*entry).ifa_next;
        }
    }

    head
}

/// Free a list produced by `deserialize`.
///
/// This must not be used for a list returned by glibc `getifaddrs`; the
/// preload client tracks ownership and delegates those lists to libc.
///
/// # Safety
///
/// `cursor` must be null or be the head pointer returned by [`deserialize`].
/// Passing any other allocation, or freeing the same list twice, is undefined.
pub unsafe fn free_list(mut cursor: *mut libc::ifaddrs) {
    while !cursor.is_null() {
        let entry = unsafe { &mut *cursor };
        let next = entry.ifa_next;
        unsafe {
            libc::free(entry.ifa_name as *mut libc::c_void);
            libc::free(entry.ifa_addr as *mut libc::c_void);
            libc::free(entry.ifa_netmask as *mut libc::c_void);
            libc::free(entry.ifa_ifu as *mut libc::c_void);
            libc::free(entry.ifa_data);
            libc::free(cursor as *mut libc::c_void);
        }
        cursor = next;
    }
}

fn append_u32(output: &mut Vec<u8>, value: u32) -> Option<()> {
    if output.len().checked_add(4)? > MAX_PAYLOAD {
        return None;
    }
    output.extend_from_slice(&value.to_be_bytes());
    Some(())
}

fn append_bytes(output: &mut Vec<u8>, value: &[u8]) -> Option<()> {
    if output.len().checked_add(value.len())? > MAX_PAYLOAD {
        return None;
    }
    output.extend_from_slice(value);
    Some(())
}

unsafe fn append_field(output: &mut Vec<u8>, address: *const libc::sockaddr) -> Option<()> {
    if address.is_null() {
        return append_u32(output, 0);
    }
    let length = unsafe { sockaddr_length(address) };
    append_u32(output, length as u32)?;
    if output.len().checked_add(length)? > MAX_PAYLOAD {
        return None;
    }
    let bytes = unsafe { slice::from_raw_parts(address as *const u8, length) };
    output.extend_from_slice(bytes);
    Some(())
}

unsafe fn sockaddr_length(address: *const libc::sockaddr) -> usize {
    match unsafe { (*address).sa_family as i32 } {
        libc::AF_INET => size_of::<libc::sockaddr_in>(),
        libc::AF_INET6 => size_of::<libc::sockaddr_in6>(),
        libc::AF_PACKET => size_of::<libc::sockaddr_ll>(),
        _ => size_of::<libc::sockaddr>(),
    }
}

unsafe fn copy_field(bytes: &[u8]) -> *mut libc::sockaddr {
    if bytes.is_empty() {
        return std::ptr::null_mut();
    }
    if bytes.len() > size_of::<libc::sockaddr_storage>() {
        return std::ptr::null_mut();
    }
    let copy = unsafe { libc::malloc(bytes.len()) } as *mut libc::sockaddr;
    if !copy.is_null() {
        unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), copy as *mut u8, bytes.len());
        }
    }
    copy
}

struct Cursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.offset)
    }

    fn u32(&mut self) -> Option<u32> {
        let end = self.offset.checked_add(4)?;
        let value = u32::from_be_bytes(self.bytes.get(self.offset..end)?.try_into().ok()?);
        self.offset = end;
        Some(value)
    }

    fn field(&mut self) -> Option<&'a [u8]> {
        let length = self.u32()? as usize;
        let end = self.offset.checked_add(length)?;
        let value = self.bytes.get(self.offset..end)?;
        self.offset = end;
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn headers_are_big_endian_and_bounded() {
        let header = response_header(17).unwrap();
        assert_eq!(&header[..4], MAGIC);
        assert_eq!(payload_len(&header), Some(17));
        assert!(!is_zero_request(&header));
        assert!(response_header(MAX_PAYLOAD + 1).is_none());
    }

    #[test]
    fn malformed_payload_is_rejected() {
        let payload = [0, 0, 0, 4, 1];
        assert!(unsafe { deserialize(&payload) }.is_null());
    }
}
