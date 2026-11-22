#!/usr/bin/env python3
"""Capture the root X11 window without depending on Xlib or ImageMagick."""

import argparse
import os
import socket
import struct
import time
import sys
import zlib


class X11Error(RuntimeError):
    pass


def read_exact(stream, size):
    chunks = []
    remaining = size
    while remaining:
        chunk = stream.recv(remaining)
        if not chunk:
            raise X11Error("X11 connection closed while reading")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def padded(value, alignment):
    return (value + alignment - 1) // alignment * alignment


def connect(display):
    number = display.rsplit(":", 1)[-1].split(".", 1)[0]
    if not number.isdigit():
        raise X11Error(f"invalid DISPLAY: {display}")
    path = f"/tmp/.X11-unix/X{number}"
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.settimeout(8)
    connection.connect(path)
    connection.sendall(struct.pack("<BBHHHH2s", ord("l"), 0, 11, 0, 0, 0, b""))
    prefix = read_exact(connection, 8)
    if prefix[0] != 1:
        reason_length = prefix[1]
        units = struct.unpack_from("<H", prefix, 6)[0]
        reason = read_exact(connection, units * 4) if units else b""
        raise X11Error(reason[:reason_length].decode("utf-8", "replace") or "X11 setup rejected")
    units = struct.unpack_from("<H", prefix, 6)[0]
    return connection, read_exact(connection, units * 4)


def parse_setup(data):
    if len(data) < 32:
        raise X11Error("short X11 setup reply")
    vendor_length = struct.unpack_from("<H", data, 16)[0]
    screen_count = data[20]
    format_count = data[21]
    if screen_count < 1:
        raise X11Error("X11 server has no screen")

    offset = 32 + padded(vendor_length, 4)
    formats = {}
    for _ in range(format_count):
        depth, bits_per_pixel, scanline_pad = data[offset:offset + 3]
        formats[depth] = (bits_per_pixel, scanline_pad)
        offset += 8

    screen = offset
    root_window = struct.unpack_from("<I", data, screen)[0]
    width = struct.unpack_from("<H", data, screen + 20)[0]
    height = struct.unpack_from("<H", data, screen + 22)[0]
    root_visual = struct.unpack_from("<I", data, screen + 32)[0]
    root_depth = data[screen + 38]
    depth_count = data[screen + 39]

    visual_offset = screen + 40
    masks = None
    for _ in range(depth_count):
        depth = data[visual_offset]
        visual_count = struct.unpack_from("<H", data, visual_offset + 2)[0]
        visual_offset += 8
        for _ in range(visual_count):
            visual_id = struct.unpack_from("<I", data, visual_offset)[0]
            if depth == root_depth and visual_id == root_visual:
                masks = struct.unpack_from("<III", data, visual_offset + 8)
            visual_offset += 24

    bits_per_pixel, scanline_pad = formats.get(
        root_depth,
        (32 if root_depth > 24 else root_depth, 32),
    )
    red, green, blue = masks or (0x00FF0000, 0x0000FF00, 0x000000FF)
    return {
        "root": root_window,
        "width": width,
        "height": height,
        "depth": root_depth,
        "bits_per_pixel": bits_per_pixel,
        "scanline_pad": scanline_pad,
        "red": red,
        "green": green,
        "blue": blue,
    }


def get_image(connection, screen):
    request = struct.pack(
        "<BBHIHHHHI",
        73,
        2,
        5,
        screen["root"],
        0,
        0,
        screen["width"],
        screen["height"],
        0xFFFFFFFF,
    )
    connection.sendall(request)
    reply = read_exact(connection, 32)
    if reply[0] != 1:
        raise X11Error(f"GetImage failed with reply type {reply[0]}")
    payload_size = struct.unpack_from("<I", reply, 4)[0] * 4
    if payload_size <= 0 or payload_size > 256 * 1024 * 1024:
        raise X11Error(f"invalid GetImage payload: {payload_size}")
    return read_exact(connection, payload_size)


def channel(value, mask):
    if not mask:
        return 0
    shift = (mask & -mask).bit_length() - 1
    maximum = mask >> shift
    raw = (value & mask) >> shift
    return (raw * 255 + maximum // 2) // maximum


def rgb_rows(screen, payload):
    width = screen["width"]
    height = screen["height"]
    bits = screen["bits_per_pixel"]
    bytes_per_pixel = bits // 8
    row_bits = width * bits
    stride = (padded(row_bits, screen["scanline_pad"])) // 8
    required = stride * height
    if len(payload) < required:
        raise X11Error(f"truncated image payload: {len(payload)} < {required}")

    standard = (screen["red"], screen["green"], screen["blue"]) == (
        0x00FF0000,
        0x0000FF00,
        0x000000FF,
    )
    rows = []
    for y in range(height):
        row_start = y * stride
        row = bytearray(width * 3)
        if standard and bytes_per_pixel in (3, 4) and row_bits % 8 == 0:
            out = 0
            for source in range(row_start, row_start + width * bytes_per_pixel, bytes_per_pixel):
                row[out] = payload[source + 2]
                row[out + 1] = payload[source + 1]
                row[out + 2] = payload[source]
                out += 3
        else:
            out = 0
            for x in range(width):
                source = row_start + x * bytes_per_pixel
                if bytes_per_pixel == 2:
                    value = struct.unpack_from("<H", payload, source)[0]
                elif bytes_per_pixel == 3:
                    value = payload[source] | payload[source + 1] << 8 | payload[source + 2] << 16
                elif bytes_per_pixel == 4:
                    value = struct.unpack_from("<I", payload, source)[0]
                else:
                    raise X11Error(f"unsupported pixel format: {bits} bpp")
                row[out] = channel(value, screen["red"])
                row[out + 1] = channel(value, screen["green"])
                row[out + 2] = channel(value, screen["blue"])
                out += 3
        rows.append(b"\x00" + row)
    return rows


def png_chunk(kind, payload):
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def encode_png(width, height, rows):
    raw = b"".join(rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(raw, 6))
        + png_chunk(b"IEND", b"")
    )


def write_png(path, png):
    temporary = f"{path}.tmp.{os.getpid()}"
    with open(temporary, "wb") as output:
        output.write(png)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def capture_png(display):
    connection = None
    try:
        connection, setup = connect(display)
        screen = parse_setup(setup)
        payload = get_image(connection, screen)
        rows = rgb_rows(screen, payload)
        return screen, encode_png(screen["width"], screen["height"], rows)
    finally:
        if connection is not None:
            connection.close()


def stream_frames(display, fps):
    interval = 1.0 / max(0.5, min(30.0, fps))
    output = sys.stdout.buffer
    while True:
        started = time.monotonic()
        try:
            screen, png = capture_png(display)
        except Exception as error:
            print(f"doro-x11-screenshot stream: {error}", file=sys.stderr, flush=True)
            time.sleep(min(2.0, max(0.25, interval)))
            continue
        output.write(struct.pack(">I", len(png)))
        output.write(png)
        output.flush()
        elapsed = time.monotonic() - started
        if elapsed < interval:
            time.sleep(interval - elapsed)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--display", default=os.environ.get("DISPLAY", ":0"))
    parser.add_argument("--output")
    parser.add_argument("--stream", action="store_true")
    parser.add_argument("--fps", type=float, default=3.0)
    args = parser.parse_args()
    if args.stream:
        try:
            stream_frames(args.display, args.fps)
            return 0
        except BrokenPipeError:
            return 0
        except KeyboardInterrupt:
            return 0
        except Exception as error:
            print(f"doro-x11-screenshot: {error}", file=sys.stderr)
            return 1
    if not args.output:
        parser.error("--output is required unless --stream is used")
    try:
        screen, png = capture_png(args.display)
        write_png(args.output, png)
        print(
            f"captured={screen['width']}x{screen['height']} "
            f"bpp={screen['bits_per_pixel']} bytes={os.path.getsize(args.output)} "
            f"output={args.output}"
        )
        return 0
    except Exception as error:
        print(f"doro-x11-screenshot: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
