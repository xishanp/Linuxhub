#!/usr/bin/env python3
"""Window, focus, wait and clipboard control for LinuxHub's X11 desktop.

The helper talks directly to libX11 through ctypes so it does not depend on
xdotool, wmctrl, xprop or an online package installation.
"""

import argparse
import ctypes
import json
import os
import re
import select
import signal
import socket
import struct
import sys
import time

DISPLAY = ctypes.c_void_p
WINDOW = ctypes.c_ulong
ATOM = ctypes.c_ulong
TIME = ctypes.c_ulong
BOOL = ctypes.c_int
LONG = ctypes.c_long

XA_ATOM = 4
XA_STRING = 31
XA_WINDOW = 33
ANY_PROPERTY_TYPE = 0
CURRENT_TIME = 0
PROPERTY_CHANGE_MASK = 1 << 22
SUBSTRUCTURE_NOTIFY_MASK = 1 << 19
SUBSTRUCTURE_REDIRECT_MASK = 1 << 20
IS_VIEWABLE = 2
SELECTION_CLEAR = 29
SELECTION_REQUEST = 30
SELECTION_NOTIFY = 31
MAX_TEXT_BYTES = 1024 * 1024

try:
    libx11 = ctypes.CDLL("libX11.so.6")
except OSError as error:
    raise SystemExit(json.dumps({
        "ok": False,
        "error": f"libX11.so.6 unavailable: {error}",
    }, ensure_ascii=False))


class XWindowAttributes(ctypes.Structure):
    _fields_ = [
        ("x", ctypes.c_int),
        ("y", ctypes.c_int),
        ("width", ctypes.c_int),
        ("height", ctypes.c_int),
        ("border_width", ctypes.c_int),
        ("depth", ctypes.c_int),
        ("visual", ctypes.c_void_p),
        ("root", WINDOW),
        ("window_class", ctypes.c_int),
        ("bit_gravity", ctypes.c_int),
        ("win_gravity", ctypes.c_int),
        ("backing_store", ctypes.c_int),
        ("backing_planes", ctypes.c_ulong),
        ("backing_pixel", ctypes.c_ulong),
        ("save_under", ctypes.c_int),
        ("colormap", ctypes.c_ulong),
        ("map_installed", ctypes.c_int),
        ("map_state", ctypes.c_int),
        ("all_event_masks", LONG),
        ("your_event_mask", LONG),
        ("do_not_propagate_mask", LONG),
        ("override_redirect", ctypes.c_int),
        ("screen", ctypes.c_void_p),
    ]


class ClientMessageData(ctypes.Union):
    _fields_ = [
        ("b", ctypes.c_char * 20),
        ("s", ctypes.c_short * 10),
        ("l", LONG * 5),
    ]


class XClientMessageEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int),
        ("serial", ctypes.c_ulong),
        ("send_event", ctypes.c_int),
        ("display", DISPLAY),
        ("window", WINDOW),
        ("message_type", ATOM),
        ("format", ctypes.c_int),
        ("data", ClientMessageData),
    ]


class XSelectionEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int),
        ("serial", ctypes.c_ulong),
        ("send_event", ctypes.c_int),
        ("display", DISPLAY),
        ("requestor", WINDOW),
        ("selection", ATOM),
        ("target", ATOM),
        ("property", ATOM),
        ("time", TIME),
    ]


class XSelectionRequestEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int),
        ("serial", ctypes.c_ulong),
        ("send_event", ctypes.c_int),
        ("display", DISPLAY),
        ("owner", WINDOW),
        ("requestor", WINDOW),
        ("selection", ATOM),
        ("target", ATOM),
        ("property", ATOM),
        ("time", TIME),
    ]


class XEvent(ctypes.Union):
    _fields_ = [
        ("type", ctypes.c_int),
        ("xclient", XClientMessageEvent),
        ("xselection", XSelectionEvent),
        ("xselectionrequest", XSelectionRequestEvent),
        ("pad", LONG * 24),
    ]


X_ERROR_HANDLER = ctypes.CFUNCTYPE(ctypes.c_int, DISPLAY, ctypes.c_void_p)


def ignore_async_x_errors(display, event):
    # Window managers routinely destroy a window between _NET_CLIENT_LIST and
    # XGetWindowProperty. Xlib's default handler exits the whole helper on that
    # expected race. Individual calls still return failure and the enumerator
    # skips the stale XID.
    return 0


_x_error_handler = X_ERROR_HANDLER(ignore_async_x_errors)
libx11.XSetErrorHandler.argtypes = [X_ERROR_HANDLER]
libx11.XSetErrorHandler.restype = ctypes.c_void_p
libx11.XSetErrorHandler(_x_error_handler)


libx11.XOpenDisplay.argtypes = [ctypes.c_char_p]
libx11.XOpenDisplay.restype = DISPLAY
libx11.XCloseDisplay.argtypes = [DISPLAY]
libx11.XCloseDisplay.restype = ctypes.c_int
libx11.XDefaultRootWindow.argtypes = [DISPLAY]
libx11.XDefaultRootWindow.restype = WINDOW
libx11.XInternAtom.argtypes = [DISPLAY, ctypes.c_char_p, BOOL]
libx11.XInternAtom.restype = ATOM
libx11.XGetAtomName.argtypes = [DISPLAY, ATOM]
libx11.XGetAtomName.restype = ctypes.c_void_p
libx11.XFree.argtypes = [ctypes.c_void_p]
libx11.XFree.restype = ctypes.c_int
libx11.XGetWindowProperty.argtypes = [
    DISPLAY, WINDOW, ATOM, LONG, LONG, BOOL, ATOM,
    ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int),
    ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong),
    ctypes.POINTER(ctypes.c_void_p),
]
libx11.XGetWindowProperty.restype = ctypes.c_int
libx11.XQueryTree.argtypes = [
    DISPLAY, WINDOW, ctypes.POINTER(ctypes.c_ulong),
    ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.POINTER(ctypes.c_ulong)),
    ctypes.POINTER(ctypes.c_uint),
]
libx11.XQueryTree.restype = ctypes.c_int
libx11.XGetWindowAttributes.argtypes = [DISPLAY, WINDOW, ctypes.POINTER(XWindowAttributes)]
libx11.XGetWindowAttributes.restype = ctypes.c_int
libx11.XTranslateCoordinates.argtypes = [
    DISPLAY, WINDOW, WINDOW, ctypes.c_int, ctypes.c_int,
    ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(WINDOW),
]
libx11.XTranslateCoordinates.restype = ctypes.c_int
libx11.XGetInputFocus.argtypes = [DISPLAY, ctypes.POINTER(WINDOW), ctypes.POINTER(ctypes.c_int)]
libx11.XGetInputFocus.restype = ctypes.c_int
libx11.XSendEvent.argtypes = [DISPLAY, WINDOW, BOOL, LONG, ctypes.c_void_p]
libx11.XSendEvent.restype = ctypes.c_int
libx11.XMapRaised.argtypes = [DISPLAY, WINDOW]
libx11.XMapRaised.restype = ctypes.c_int
libx11.XRaiseWindow.argtypes = [DISPLAY, WINDOW]
libx11.XRaiseWindow.restype = ctypes.c_int
libx11.XMoveResizeWindow.argtypes = [DISPLAY, WINDOW, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
libx11.XMoveResizeWindow.restype = ctypes.c_int
libx11.XIconifyWindow.argtypes = [DISPLAY, WINDOW, ctypes.c_int]
libx11.XIconifyWindow.restype = ctypes.c_int
libx11.XSetInputFocus.argtypes = [DISPLAY, WINDOW, ctypes.c_int, TIME]
libx11.XSetInputFocus.restype = ctypes.c_int
libx11.XFlush.argtypes = [DISPLAY]
libx11.XFlush.restype = ctypes.c_int
libx11.XSync.argtypes = [DISPLAY, BOOL]
libx11.XSync.restype = ctypes.c_int
libx11.XCreateSimpleWindow.argtypes = [
    DISPLAY, WINDOW, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint,
    ctypes.c_uint, ctypes.c_ulong, ctypes.c_ulong,
]
libx11.XCreateSimpleWindow.restype = WINDOW
libx11.XDestroyWindow.argtypes = [DISPLAY, WINDOW]
libx11.XDestroyWindow.restype = ctypes.c_int
libx11.XSelectInput.argtypes = [DISPLAY, WINDOW, LONG]
libx11.XSelectInput.restype = ctypes.c_int
libx11.XConvertSelection.argtypes = [DISPLAY, ATOM, ATOM, ATOM, WINDOW, TIME]
libx11.XConvertSelection.restype = ctypes.c_int
libx11.XNextEvent.argtypes = [DISPLAY, ctypes.c_void_p]
libx11.XNextEvent.restype = ctypes.c_int
libx11.XPending.argtypes = [DISPLAY]
libx11.XPending.restype = ctypes.c_int
libx11.XConnectionNumber.argtypes = [DISPLAY]
libx11.XConnectionNumber.restype = ctypes.c_int
libx11.XGetSelectionOwner.argtypes = [DISPLAY, ATOM]
libx11.XGetSelectionOwner.restype = WINDOW
libx11.XSetSelectionOwner.argtypes = [DISPLAY, ATOM, WINDOW, TIME]
libx11.XSetSelectionOwner.restype = ctypes.c_int
libx11.XDeleteProperty.argtypes = [DISPLAY, WINDOW, ATOM]
libx11.XDeleteProperty.restype = ctypes.c_int
libx11.XChangeProperty.argtypes = [
    DISPLAY, WINDOW, ATOM, ATOM, ctypes.c_int, ctypes.c_int,
    ctypes.c_void_p, ctypes.c_int,
]
libx11.XChangeProperty.restype = ctypes.c_int


class ControlError(RuntimeError):
    pass


class Property:
    def __init__(self, atom_type, value_format, values, raw):
        self.atom_type = atom_type
        self.format = value_format
        self.values = values
        self.raw = raw


class X11:
    def __init__(self, display_name):
        self.display_name = display_name
        self.display = libx11.XOpenDisplay(display_name.encode("utf-8"))
        if not self.display:
            raise ControlError(f"cannot open X11 display {display_name}")
        self.root = libx11.XDefaultRootWindow(self.display)
        self._atoms = {}

    def close(self):
        if self.display:
            libx11.XCloseDisplay(self.display)
            self.display = None

    def atom(self, name):
        atom = self._atoms.get(name)
        if atom is None:
            atom = int(libx11.XInternAtom(self.display, name.encode("utf-8"), 0))
            self._atoms[name] = atom
        return atom

    def atom_name(self, atom):
        if not atom:
            return ""
        pointer = libx11.XGetAtomName(self.display, atom)
        if not pointer:
            return ""
        try:
            return ctypes.string_at(pointer).decode("utf-8", "replace")
        finally:
            libx11.XFree(pointer)

    def property(self, window, name, max_units=262144):
        actual_type = ctypes.c_ulong()
        actual_format = ctypes.c_int()
        item_count = ctypes.c_ulong()
        bytes_after = ctypes.c_ulong()
        data = ctypes.c_void_p()
        status = libx11.XGetWindowProperty(
            self.display, window, self.atom(name), 0, max_units, 0,
            ANY_PROPERTY_TYPE, ctypes.byref(actual_type),
            ctypes.byref(actual_format), ctypes.byref(item_count),
            ctypes.byref(bytes_after), ctypes.byref(data),
        )
        if status != 0:
            raise ControlError(f"XGetWindowProperty failed for {name}")
        if not data or not actual_type.value or not actual_format.value:
            if data:
                libx11.XFree(data)
            return None
        try:
            if actual_format.value == 32:
                size = int(item_count.value) * ctypes.sizeof(ctypes.c_long)
                raw = ctypes.string_at(data, size)
                values = list(struct.unpack("<" + "Q" * int(item_count.value), raw)) \
                    if size else []
            elif actual_format.value == 16:
                size = int(item_count.value) * 2
                raw = ctypes.string_at(data, size)
                values = list(struct.unpack("<" + "H" * int(item_count.value), raw)) \
                    if size else []
            else:
                size = int(item_count.value)
                raw = ctypes.string_at(data, size)
                values = list(raw)
            return Property(int(actual_type.value), int(actual_format.value), values, raw)
        finally:
            libx11.XFree(data)

    def string_property(self, window, name):
        prop = self.property(window, name)
        if not prop or prop.format != 8:
            return ""
        return prop.raw.rstrip(b"\0").decode("utf-8", "replace")

    def atom_property(self, window, name):
        prop = self.property(window, name)
        if not prop or prop.format != 32:
            return []
        return [self.atom_name(int(value)) for value in prop.values if value]

    def value32(self, window, name, default=None):
        prop = self.property(window, name)
        if not prop or prop.format != 32 or not prop.values:
            return default
        return int(prop.values[0])

    def query_tree(self, window):
        root = ctypes.c_ulong()
        parent = ctypes.c_ulong()
        children = ctypes.POINTER(ctypes.c_ulong)()
        count = ctypes.c_uint()
        if not libx11.XQueryTree(
            self.display, window, ctypes.byref(root), ctypes.byref(parent),
            ctypes.byref(children), ctypes.byref(count),
        ):
            raise ControlError("XQueryTree failed")
        try:
            return int(root.value), int(parent.value), [int(children[i]) for i in range(count.value)]
        finally:
            if children:
                libx11.XFree(children)

    def attributes(self, window):
        attrs = XWindowAttributes()
        if not libx11.XGetWindowAttributes(self.display, window, ctypes.byref(attrs)):
            return None
        return attrs

    def absolute_geometry(self, window):
        attrs = self.attributes(window)
        if attrs is None:
            return None
        x = ctypes.c_int()
        y = ctypes.c_int()
        child = WINDOW()
        translated = libx11.XTranslateCoordinates(
            self.display, window, self.root, 0, 0,
            ctypes.byref(x), ctypes.byref(y), ctypes.byref(child),
        )
        return {
            "x": int(x.value) if translated else int(attrs.x),
            "y": int(y.value) if translated else int(attrs.y),
            "width": int(attrs.width),
            "height": int(attrs.height),
            "borderWidth": int(attrs.border_width),
            "mapped": int(attrs.map_state) == IS_VIEWABLE,
        }

    def active_window(self):
        return int(self.value32(self.root, "_NET_ACTIVE_WINDOW", 0) or 0)

    def focus_window(self):
        focus = WINDOW()
        revert = ctypes.c_int()
        libx11.XGetInputFocus(self.display, ctypes.byref(focus), ctypes.byref(revert))
        return int(focus.value)

    def is_descendant_or_self(self, candidate, ancestor):
        current = candidate
        seen = set()
        while current and current not in seen:
            if current == ancestor:
                return True
            seen.add(current)
            try:
                _, parent, _ = self.query_tree(current)
            except ControlError:
                return False
            if parent == current:
                break
            current = parent
        return False

    def client_windows(self):
        prop = self.property(self.root, "_NET_CLIENT_LIST")
        windows = []
        if prop and prop.format == 32:
            windows = [int(value) for value in prop.values if value]
        if not windows:
            _, _, windows = self.query_tree(self.root)
        return windows

    def stacking(self):
        stacking = self.value32_list(self.root, "_NET_CLIENT_LIST_STACKING")
        return stacking

    def value32_list(self, window, name):
        prop = self.property(window, name)
        if not prop or prop.format != 32:
            return []
        return [int(value) for value in prop.values if value]

    def window_info(self, window, active, focus, stack_index):
        attrs = self.attributes(window)
        if attrs is None:
            return None
        geometry = self.absolute_geometry(window)
        title = self.string_property(window, "_NET_WM_NAME")
        if not title:
            title = self.string_property(window, "WM_NAME")
        instance = ""
        wm_class = ""
        class_prop = self.property(window, "WM_CLASS")
        if class_prop and class_prop.format == 8:
            parts = class_prop.raw.rstrip(b"\0").split(b"\0")
            if parts:
                instance = parts[0].decode("utf-8", "replace")
            if len(parts) > 1:
                wm_class = parts[1].decode("utf-8", "replace")
        pid = self.value32(window, "_NET_WM_PID")
        desktop = self.value32(window, "_NET_WM_DESKTOP")
        if desktop is not None and desktop >= 0xFFFFFFFF:
            desktop = -1
        _, parent, _ = self.query_tree(window)
        states = self.atom_property(window, "_NET_WM_STATE")
        types = self.atom_property(window, "_NET_WM_WINDOW_TYPE")
        focused = bool(active == window or self.is_descendant_or_self(focus, window))
        hidden = (
            "hidden" in [state.removeprefix("_NET_WM_STATE_").lower() for state in states]
            or int(attrs.map_state) != IS_VIEWABLE
        )
        return {
            "id": window,
            "windowId": f"0x{window:x}",
            "title": title,
            "instance": instance,
            "wmClass": wm_class,
            "pid": pid,
            "desktop": desktop,
            "geometry": {
                "x": geometry["x"] if geometry else int(attrs.x),
                "y": geometry["y"] if geometry else int(attrs.y),
                "width": int(attrs.width),
                "height": int(attrs.height),
            },
            "mapped": bool(geometry and geometry["mapped"]),
            "hidden": hidden,
            "focused": focused,
            "active": window == active,
            "frameId": parent,
            "stackIndex": stack_index,
            "states": states,
            "windowTypes": types,
            "transientFor": self.value32(window, "WM_TRANSIENT_FOR"),
            "role": self.string_property(window, "WM_WINDOW_ROLE"),
        }

    def windows(self):
        active = self.active_window()
        focus = self.focus_window()
        stacking = self.stacking()
        stack_positions = {window: index for index, window in enumerate(stacking)}
        windows = []
        for index, window in enumerate(self.client_windows()):
            try:
                info = self.window_info(
                    window, active, focus, stack_positions.get(window, index),
                )
            except ControlError:
                # A client disappeared after _NET_CLIENT_LIST was read.
                continue
            if info is not None:
                windows.append(info)
        if stacking:
            windows.sort(key=lambda item: item["stackIndex"], reverse=True)
        return {
            "activeWindowId": f"0x{active:x}" if active else None,
            "focusWindowId": f"0x{focus:x}" if focus else None,
            "windows": windows,
        }


def decode_window_id(value):
    if value is None:
        return None
    text = str(value).strip().lower()
    try:
        parsed = int(text, 16) if text.startswith("0x") else int(text, 10)
    except ValueError:
        raise ControlError(f"invalid window id: {value}")
    if parsed <= 0 or parsed > 0xFFFFFFFF:
        raise ControlError(f"window id out of range: {value}")
    return parsed


def title_matches(title, expected, mode):
    if mode == "regex":
        flags = re.IGNORECASE
        if not re.search(expected, title, flags):
            return False
        return True
    actual = title.casefold()
    wanted = expected.casefold()
    if mode == "exact":
        return actual == wanted
    if mode == "startswith":
        return actual.startswith(wanted)
    return wanted in actual


def class_matches(window, expected, mode):
    values = [window.get("wmClass") or "", window.get("instance") or ""]
    wanted = expected.casefold()
    if mode == "exact":
        return any(value.casefold() == wanted for value in values)
    return any(wanted in value.casefold() for value in values)


def matching_windows(windows, args):
    result = []
    wanted_id = decode_window_id(getattr(args, "window_id", None))
    for window in windows:
        if wanted_id is not None and window["id"] != wanted_id:
            continue
        if getattr(args, "title", None):
            mode = getattr(args, "title_match", "contains")
            try:
                if not title_matches(window["title"], args.title, mode):
                    continue
            except re.error as error:
                raise ControlError(f"invalid title regex: {error}")
        if getattr(args, "wm_class", None):
            mode = getattr(args, "class_match", "exact")
            if not class_matches(window, args.wm_class, mode):
                continue
        if getattr(args, "pid", None) is not None and window.get("pid") != args.pid:
            continue
        result.append(window)
    return result


def selector_present(args):
    return any([
        getattr(args, "window_id", None),
        getattr(args, "title", None),
        getattr(args, "wm_class", None),
        getattr(args, "pid", None) is not None,
    ])


def resolve_one(windows, args):
    if not selector_present(args):
        raise ControlError("window selector required: window_id, title, wm_class or pid")
    matches = matching_windows(windows, args)
    if not matches:
        raise ControlError("window not found")
    if len(matches) > 1:
        summaries = [
            {
                "windowId": item["windowId"],
                "title": item["title"],
                "wmClass": item["wmClass"],
                "pid": item.get("pid"),
            }
            for item in matches[:8]
        ]
        raise ControlError("window selector is ambiguous; use window_id or a narrower selector: " +
                           json.dumps(summaries, ensure_ascii=False))
    return matches[0]


def send_client_message(x11, window, message_name, values):
    event = XEvent()
    event.xclient.type = 33  # ClientMessage
    event.xclient.serial = 0
    event.xclient.send_event = 1
    event.xclient.display = x11.display
    event.xclient.window = window
    event.xclient.message_type = x11.atom(message_name)
    event.xclient.format = 32
    for index, value in enumerate(values[:5]):
        event.xclient.data.l[index] = int(value)
    mask = SUBSTRUCTURE_REDIRECT_MASK | SUBSTRUCTURE_NOTIFY_MASK
    if not libx11.XSendEvent(
        x11.display, x11.root, 0, mask, ctypes.byref(event.xclient)
    ):
        raise ControlError(f"XSendEvent failed for {message_name}")
    libx11.XFlush(x11.display)


def refresh_windows(x11):
    snapshot = x11.windows()
    snapshot["count"] = len(snapshot["windows"])
    return snapshot


def windows_command(x11, args):
    snapshot = refresh_windows(x11)
    windows = snapshot["windows"]
    if args.query:
        query = args.query.casefold()
        windows = [
            window for window in windows
            if query in (window.get("title") or "").casefold()
            or query in (window.get("wmClass") or "").casefold()
            or query in (window.get("instance") or "").casefold()
        ]
    if args.wm_class:
        windows = [window for window in windows if class_matches(window, args.wm_class, args.class_match)]
    if args.pid is not None:
        windows = [window for window in windows if window.get("pid") == args.pid]
    if not args.include_hidden:
        windows = [window for window in windows if not window["hidden"]]
    total = len(windows)
    windows = windows[:args.limit]
    return {
        "ok": True,
        "display": x11.display_name,
        "activeWindowId": snapshot["activeWindowId"],
        "focusWindowId": snapshot["focusWindowId"],
        "count": total,
        "returned": len(windows),
        "truncated": len(windows) < total,
        "windows": windows,
    }


def activate_command(x11, args):
    target = resolve_one(refresh_windows(x11)["windows"], args)
    if target["focused"]:
        return {"ok": True, "alreadyActive": True, "window": target}
    send_client_message(
        x11, target["id"], "_NET_ACTIVE_WINDOW",
        [1, CURRENT_TIME, x11.active_window()],
    )
    libx11.XMapRaised(x11.display, target["id"])
    libx11.XRaiseWindow(x11.display, target["id"])
    libx11.XFlush(x11.display)
    deadline = time.monotonic() + max(0, args.timeout_ms) / 1000.0
    method = "ewmh"
    fallback_sent = False
    while True:
        windows = refresh_windows(x11)["windows"]
        current = next((window for window in windows if window["id"] == target["id"]), None)
        if current and current["focused"]:
            return {
                "ok": True,
                "alreadyActive": False,
                "method": method,
                "window": current,
            }
        if time.monotonic() >= deadline:
            break
        if not fallback_sent and time.monotonic() + 0.3 >= deadline:
            method = "raise+focus"
            fallback_sent = True
            libx11.XRaiseWindow(x11.display, target["id"])
            libx11.XSetInputFocus(x11.display, target["id"], 1, CURRENT_TIME)
            libx11.XFlush(x11.display)
        time.sleep(0.05)
    current = next(
        (window for window in refresh_windows(x11)["windows"] if window["id"] == target["id"]),
        target,
    )
    raise ControlError("window activation timed out; current=" +
                       json.dumps(current, ensure_ascii=False))


def close_command(x11, args):
    started = time.monotonic()
    target = resolve_one(refresh_windows(x11)["windows"], args)
    send_client_message(
        x11, target["id"], "_NET_CLOSE_WINDOW",
        [CURRENT_TIME, 1],
    )
    deadline = time.monotonic() + max(0, args.timeout_ms) / 1000.0
    while True:
        present = any(
            window["id"] == target["id"] for window in refresh_windows(x11)["windows"]
        )
        if not present:
            return {
                "ok": True,
                "closed": True,
                "window": target,
                "elapsedMs": int((time.monotonic() - started) * 1000),
            }
        if time.monotonic() >= deadline:
            return {
                "ok": False,
                "closed": False,
                "window": target,
                "error": "close request was sent but the window is still open",
            }
        time.sleep(0.1)


def current_window(x11, window_id):
    return next(
        (window for window in refresh_windows(x11)["windows"] if window["id"] == window_id),
        None,
    )


def send_wm_state(x11, window, state, action, second_state=None):
    values = [action, x11.atom(state), x11.atom(second_state) if second_state else 0, 2]
    send_client_message(x11, window["id"], "_NET_WM_STATE", values)


def geometry_matches(window, expected):
    geometry = window.get("geometry") or {}
    return all(geometry.get(key) == value for key, value in expected.items())


def window_state_names(window):
    prefix = "_NET_WM_STATE_"
    return {
        state.removeprefix(prefix).lower()
        for state in (window.get("states") or [])
        if state.startswith(prefix)
    }


def window_command(x11, args):
    started = time.monotonic()
    target = resolve_one(refresh_windows(x11)["windows"], args)
    before = target
    action = args.action
    expected_geometry = None
    expected_state = None
    expected_hidden = None
    method = None

    if action in {"move", "resize", "set_geometry"}:
        current = target["geometry"]
        x = current["x"] if args.x is None else int(args.x)
        y = current["y"] if args.y is None else int(args.y)
        width = current["width"] if args.width is None else int(args.width)
        height = current["height"] if args.height is None else int(args.height)
        if not (-65536 <= x <= 65536 and -65536 <= y <= 65536):
            raise ControlError("window position is out of range")
        if not (64 <= width <= 16384 and 64 <= height <= 16384):
            raise ControlError("window size must be between 64 and 16384")
        changed = {
            "x": x, "y": y, "width": width, "height": height,
        }
        if changed == current:
            return {
                "ok": True,
                "action": action,
                "changed": False,
                "before": before,
                "after": before,
                "elapsedMs": int((time.monotonic() - started) * 1000),
            }
        # EWMH gravity/source flags: StaticGravity, all four geometry fields,
        # source indication 2 (direct user action). This lets xfwm4 apply frame
        # aware placement instead of bypassing the window manager.
        flags = 10 | (1 << 8) | (1 << 9) | (1 << 10) | (1 << 11) | (2 << 12)
        send_client_message(
            x11, target["id"], "_NET_MOVERESIZE_WINDOW",
            [flags, x, y, width, height],
        )
        method = "ewmh"
        expected_geometry = changed
    elif action in {"maximize", "unmaximize"}:
        add = action == "maximize"
        send_wm_state(
            x11, target, "_NET_WM_STATE_MAXIMIZED_VERT",
            1 if add else 0, "_NET_WM_STATE_MAXIMIZED_HORZ",
        )
        method = "ewmh"
        expected_state = {"maximized_vert", "maximized_horz"} if add else set()
    elif action in {"fullscreen", "unfullscreen"}:
        add = action == "fullscreen"
        send_wm_state(x11, target, "_NET_WM_STATE_FULLSCREEN", 1 if add else 0)
        method = "ewmh"
        expected_state = {"fullscreen"} if add else set()
    elif action == "minimize":
        if before["hidden"]:
            return {
                "ok": True,
                "action": action,
                "changed": False,
                "before": before,
                "after": before,
                "elapsedMs": int((time.monotonic() - started) * 1000),
            }
        if not libx11.XIconifyWindow(x11.display, target["id"], 0):
            raise ControlError("XIconifyWindow failed")
        libx11.XFlush(x11.display)
        method = "wm-iconify"
        expected_hidden = True
    elif action == "restore":
        libx11.XMapRaised(x11.display, target["id"])
        send_client_message(
            x11, target["id"], "_NET_ACTIVE_WINDOW",
            [2, CURRENT_TIME, x11.active_window()],
        )
        libx11.XFlush(x11.display)
        method = "map+activate"
        expected_hidden = False
    else:
        raise ControlError(f"unsupported window action: {action}")

    deadline = time.monotonic() + max(0.1, args.timeout_ms / 1000.0)
    after = target
    while True:
        current = current_window(x11, target["id"])
        if current is not None:
            after = current
            verified = True
            if expected_geometry is not None:
                verified = geometry_matches(current, expected_geometry)
            if expected_hidden is not None:
                verified = current["hidden"] is expected_hidden
            if expected_state is not None:
                states = window_state_names(current)
                if action in {"maximize", "unmaximize"}:
                    verified = (
                        {"maximized_vert", "maximized_horz"}.issubset(states)
                        if action == "maximize"
                        else not ({"maximized_vert", "maximized_horz"} & states)
                    )
                elif action in {"fullscreen", "unfullscreen"}:
                    verified = ("fullscreen" in states) == (action == "fullscreen")
            if verified:
                return {
                    "ok": True,
                    "action": action,
                    "changed": True,
                    "method": method,
                    "before": before,
                    "after": current,
                    "elapsedMs": int((time.monotonic() - started) * 1000),
                }
        if time.monotonic() >= deadline:
            break
        time.sleep(0.05)

    if after is None:
        raise ControlError("window disappeared during operation")
    raise ControlError(
        "window operation was not confirmed; expected=" +
        json.dumps({
            "geometry": expected_geometry,
            "hidden": expected_hidden,
            "state": sorted(expected_state) if expected_state is not None else None,
        }, ensure_ascii=False) +
        " actual=" + json.dumps(after, ensure_ascii=False)
    )


def wait_command(x11, args):
    started = time.monotonic()
    deadline = started + max(1, args.timeout_ms) / 1000.0
    if args.condition in {"disappears", "focused", "title-changed"} and not selector_present(args):
        raise ControlError(f"{args.condition} requires a window selector")
    baseline = None
    if args.condition == "title-changed":
        baseline = resolve_one(refresh_windows(x11)["windows"], args)
    last_error = None
    while True:
        try:
            snapshot = refresh_windows(x11)
            if args.condition == "appears":
                matches = matching_windows(snapshot["windows"], args) if selector_present(args) else snapshot["windows"]
                if matches:
                    return {
                        "ok": True,
                        "condition": args.condition,
                        "matched": True,
                        "elapsedMs": int((time.monotonic() - started) * 1000),
                        "window": matches[0],
                    }
            elif args.condition == "disappears":
                matches = matching_windows(snapshot["windows"], args)
                if not matches:
                    return {
                        "ok": True,
                        "condition": args.condition,
                        "matched": True,
                        "elapsedMs": int((time.monotonic() - started) * 1000),
                    }
            elif args.condition == "focused":
                matches = matching_windows(snapshot["windows"], args)
                focused = next((window for window in matches if window["focused"]), None)
                if focused:
                    return {
                        "ok": True,
                        "condition": args.condition,
                        "matched": True,
                        "elapsedMs": int((time.monotonic() - started) * 1000),
                        "window": focused,
                    }
            elif args.condition == "title-changed":
                current = next(
                    (window for window in snapshot["windows"] if window["id"] == baseline["id"]),
                    None,
                )
                if current and current["title"] != baseline["title"]:
                    return {
                        "ok": True,
                        "condition": args.condition,
                        "matched": True,
                        "elapsedMs": int((time.monotonic() - started) * 1000),
                        "previousTitle": baseline["title"],
                        "window": current,
                    }
            elif args.condition == "window-list-changed":
                fingerprint = [
                    (window["id"], window.get("title"), window.get("focused"))
                    for window in snapshot["windows"]
                ]
                if baseline is None:
                    baseline = fingerprint
                elif fingerprint != baseline:
                    return {
                        "ok": True,
                        "condition": args.condition,
                        "matched": True,
                        "elapsedMs": int((time.monotonic() - started) * 1000),
                        "windows": snapshot["windows"],
                    }
        except ControlError as error:
            last_error = str(error)
        if time.monotonic() >= deadline:
            return {
                "ok": True,
                "condition": args.condition,
                "matched": False,
                "elapsedMs": int((time.monotonic() - started) * 1000),
                "error": last_error or "wait timed out",
            }
        time.sleep(max(0.05, min(1.0, args.poll_ms / 1000.0)))


def selection_event(display, timeout):
    fd = libx11.XConnectionNumber(display)
    deadline = time.monotonic() + timeout
    event = XEvent()
    while time.monotonic() < deadline:
        if libx11.XPending(display):
            libx11.XNextEvent(display, ctypes.byref(event))
            return event
        readable, _, _ = select.select([fd], [], [], min(0.1, deadline - time.monotonic()))
        if readable:
            libx11.XNextEvent(display, ctypes.byref(event))
            return event
    return None


def clipboard_get(x11, timeout_ms):
    clipboard = x11.atom("CLIPBOARD")
    owner = int(libx11.XGetSelectionOwner(x11.display, clipboard))
    if not owner:
        return {"ok": True, "owner": False, "text": "", "bytes": 0}
    requestor = int(libx11.XCreateSimpleWindow(
        x11.display, x11.root, -10, -10, 1, 1, 0, 0, 0,
    ))
    if not requestor:
        raise ControlError("failed to create clipboard request window")
    try:
        libx11.XSelectInput(x11.display, requestor, PROPERTY_CHANGE_MASK)
        targets = ["UTF8_STRING", "text/plain;charset=utf-8", "TEXT", "STRING"]
        for target_name in targets:
            target = x11.atom(target_name)
            property_atom = x11.atom(f"_LINUXHUB_CLIPBOARD_{os.getpid()}_{target}")
            libx11.XDeleteProperty(x11.display, requestor, property_atom)
            libx11.XConvertSelection(
                x11.display, clipboard, target, property_atom, requestor, CURRENT_TIME,
            )
            libx11.XFlush(x11.display)
            deadline = time.monotonic() + max(0.1, timeout_ms / 1000.0)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                event = selection_event(x11.display, remaining)
                if event is None:
                    break
                if event.type != SELECTION_NOTIFY:
                    continue
                selection = event.xselection
                if selection.requestor != requestor or selection.selection != clipboard:
                    continue
                if selection.target != target:
                    continue
                if not selection.property:
                    break
                prop = x11.property(requestor, f"_LINUXHUB_CLIPBOARD_{os.getpid()}_{target}")
                libx11.XDeleteProperty(x11.display, requestor, property_atom)
                if prop and prop.format == 8:
                    raw = prop.raw.rstrip(b"\0")
                    text = raw.decode("utf-8", "replace")
                    return {
                        "ok": True,
                        "owner": True,
                        "ownerWindowId": f"0x{owner:x}",
                        "target": target_name,
                        "text": text,
                        "bytes": len(raw),
                        "truncated": len(raw) >= MAX_TEXT_BYTES,
                    }
                break
        raise ControlError("clipboard owner did not provide UTF-8 text")
    finally:
        libx11.XDestroyWindow(x11.display, requestor)
        libx11.XFlush(x11.display)


def write_selection_property(x11, requestor, property_atom, target_name, text, targets):
    if target_name == "TARGETS":
        values = (ctypes.c_ulong * len(targets))(*[x11.atom(name) for name in targets])
        libx11.XChangeProperty(
            x11.display, requestor, property_atom, XA_ATOM, 32, 0,
            ctypes.cast(values, ctypes.c_void_p), len(targets),
        )
        return True
    if target_name in {"UTF8_STRING", "text/plain;charset=utf-8", "TEXT", "STRING"}:
        raw = text.encode("utf-8")
        if len(raw) > MAX_TEXT_BYTES:
            raise ControlError("clipboard text exceeds 1 MiB")
        buffer = ctypes.create_string_buffer(raw)
        property_type = XA_STRING if target_name == "STRING" else x11.atom("UTF8_STRING")
        libx11.XChangeProperty(
            x11.display, requestor, property_atom, property_type, 8, 0,
            ctypes.cast(buffer, ctypes.c_void_p), len(raw),
        )
        return True
    return False


def send_selection_notify(x11, request, property_atom):
    event = XEvent()
    event.xselection.type = SELECTION_NOTIFY
    event.xselection.send_event = 1
    event.xselection.display = x11.display
    event.xselection.requestor = request.requestor
    event.xselection.selection = request.selection
    event.xselection.target = request.target
    event.xselection.property = property_atom
    event.xselection.time = request.time
    libx11.XSendEvent(
        x11.display, request.requestor, 0, 0, ctypes.byref(event.xselection),
    )
    libx11.XFlush(x11.display)


def unix_socket_address(value):
    return "\0" + value[1:] if value.startswith("@") else value


def clipboard_daemon(x11, socket_path):
    clipboard = x11.atom("CLIPBOARD")
    owner_window = int(libx11.XCreateSimpleWindow(
        x11.display, x11.root, -10, -10, 1, 1, 0, 0, 0,
    ))
    if not owner_window:
        raise ControlError("failed to create clipboard owner window")
    targets = ["TARGETS", "UTF8_STRING", "text/plain;charset=utf-8", "TEXT", "STRING"]
    state = {"text": "", "owned": False}
    address = unix_socket_address(socket_path)
    if not socket_path.startswith("@"):
        os.makedirs(os.path.dirname(socket_path), mode=0o700, exist_ok=True)
        try:
            os.unlink(socket_path)
        except FileNotFoundError:
            pass
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(address)
    if not socket_path.startswith("@"):
        os.chmod(socket_path, 0o600)
    server.listen(8)
    server.setblocking(False)
    x_fd = libx11.XConnectionNumber(x11.display)

    def acquire():
        libx11.XSetSelectionOwner(x11.display, clipboard, owner_window, CURRENT_TIME)
        libx11.XSync(x11.display, 0)
        state["owned"] = int(libx11.XGetSelectionOwner(x11.display, clipboard)) == owner_window
        if not state["owned"]:
            raise ControlError("failed to acquire CLIPBOARD selection")

    def handle_client(connection):
        payload = bytearray()
        connection.settimeout(1.0)
        while len(payload) <= MAX_TEXT_BYTES * 2 + 4096:
            chunk = connection.recv(65536)
            if not chunk:
                break
            payload.extend(chunk)
            if b"\n" in chunk:
                break
        if not payload:
            return
        request = json.loads(bytes(payload).split(b"\n", 1)[0].decode("utf-8"))
        action = request.get("action")
        if action == "ping":
            response = {"ok": True, "owned": state["owned"], "bytes": len(state["text"].encode("utf-8"))}
        elif action == "set":
            text = request.get("text")
            if not isinstance(text, str):
                raise ControlError("clipboard set text must be a string")
            raw = text.encode("utf-8")
            if len(raw) > MAX_TEXT_BYTES:
                raise ControlError("clipboard text exceeds 1 MiB")
            state["text"] = text
            acquire()
            response = {
                "ok": True,
                "written": True,
                "bytes": len(raw),
                "ownerWindowId": f"0x{owner_window:x}",
                "ownerPid": os.getpid(),
            }
        else:
            raise ControlError(f"unknown clipboard daemon action: {action}")
        connection.sendall(json.dumps(response, ensure_ascii=False).encode("utf-8") + b"\n")

    try:
        while True:
            readable, _, _ = select.select([x_fd, server], [], [], 30.0)
            for source in readable:
                if source == server:
                    connection, _ = server.accept()
                    try:
                        handle_client(connection)
                    except Exception as error:
                        connection.sendall(json.dumps({
                            "ok": False, "error": str(error),
                        }, ensure_ascii=False).encode("utf-8") + b"\n")
                    finally:
                        connection.close()
                else:
                    event = XEvent()
                    while libx11.XPending(x11.display):
                        libx11.XNextEvent(x11.display, ctypes.byref(event))
                        if event.type == SELECTION_CLEAR:
                            if event.xselection.selection == clipboard:
                                state["owned"] = False
                        elif event.type == SELECTION_REQUEST:
                            request = event.xselectionrequest
                            property_atom = request.property or request.target
                            target_name = x11.atom_name(request.target)
                            try:
                                accepted = write_selection_property(
                                    x11, request.requestor, property_atom,
                                    target_name, state["text"], targets,
                                )
                            except Exception:
                                accepted = False
                            send_selection_notify(x11, request, property_atom if accepted else 0)
    finally:
        if not socket_path.startswith("@"):
            try:
                os.unlink(socket_path)
            except FileNotFoundError:
                pass
        server.close()
        libx11.XDestroyWindow(x11.display, owner_window)
        libx11.XFlush(x11.display)


def clipboard_socket_command(socket_path, text):
    if not socket_path.startswith("@") and not os.path.exists(socket_path):
        raise ControlError(
            f"clipboard daemon socket is unavailable: {socket_path}; restart the LinuxHub desktop"
        )
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.settimeout(3.0)
    try:
        connection.connect(unix_socket_address(socket_path))
        payload = json.dumps({"action": "set", "text": text}, ensure_ascii=False)
        connection.sendall(payload.encode("utf-8") + b"\n")
        response = bytearray()
        while b"\n" not in response and len(response) < 65536:
            chunk = connection.recv(65536)
            if not chunk:
                break
            response.extend(chunk)
        if not response:
            raise ControlError("clipboard daemon closed without a response")
        result = json.loads(bytes(response).split(b"\n", 1)[0].decode("utf-8"))
        if not result.get("ok"):
            raise ControlError(result.get("error", "clipboard daemon failed"))
        return result
    finally:
        connection.close()


def clipboard_command(x11, args):
    if args.action == "get":
        return clipboard_get(x11, args.timeout_ms)
    text = args.text or ""
    if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
        raise ControlError("clipboard text exceeds 1 MiB")
    if args.action == "clear":
        libx11.XSetSelectionOwner(
            x11.display, x11.atom("CLIPBOARD"), 0, CURRENT_TIME,
        )
        libx11.XSync(x11.display, 0)
        return {"ok": True, "cleared": True}
    socket_path = args.socket
    if not socket_path:
        runtime = os.environ.get("XDG_RUNTIME_DIR")
        if not runtime:
            raise ControlError("clipboard daemon socket path is unknown; pass --socket")
        socket_path = os.path.join(runtime, "linuxhub", "clipboard.sock")
    return clipboard_socket_command(socket_path, text)


def add_selector_arguments(parser, include_id=True):
    if include_id:
        parser.add_argument("--window-id")
    parser.add_argument("--title")
    parser.add_argument("--title-match", choices=["contains", "exact", "startswith", "regex"], default="contains")
    parser.add_argument("--wm-class")
    parser.add_argument("--class-match", choices=["exact", "contains"], default="exact")
    parser.add_argument("--pid", type=int)


def build_parser():
    parser = argparse.ArgumentParser()
    parser.add_argument("--display", default=os.environ.get("DISPLAY") or ":0")
    sub = parser.add_subparsers(dest="command", required=True)

    windows = sub.add_parser("windows")
    windows.add_argument("--query")
    windows.add_argument("--wm-class")
    windows.add_argument("--class-match", choices=["exact", "contains"], default="exact")
    windows.add_argument("--pid", type=int)
    windows.add_argument("--include-hidden", action="store_true")
    windows.add_argument("--limit", type=int, default=200)

    resolve = sub.add_parser("resolve")
    add_selector_arguments(resolve)

    activate = sub.add_parser("activate")
    add_selector_arguments(activate)
    activate.add_argument("--timeout-ms", type=int, default=1500)

    close = sub.add_parser("close")
    add_selector_arguments(close)
    close.add_argument("--timeout-ms", type=int, default=2500)

    window = sub.add_parser("window")
    window.add_argument("action", choices=[
        "move", "resize", "set_geometry", "maximize", "unmaximize",
        "fullscreen", "unfullscreen", "minimize", "restore",
    ])
    add_selector_arguments(window)
    window.add_argument("--x", type=int)
    window.add_argument("--y", type=int)
    window.add_argument("--width", type=int)
    window.add_argument("--height", type=int)
    window.add_argument("--timeout-ms", type=int, default=2500)

    wait = sub.add_parser("wait")
    wait.add_argument("--condition", choices=[
        "appears", "disappears", "focused", "title-changed", "window-list-changed",
    ], required=True)
    add_selector_arguments(wait)
    wait.add_argument("--timeout-ms", type=int, default=10000)
    wait.add_argument("--poll-ms", type=int, default=150)

    clipboard = sub.add_parser("clipboard")
    clipboard.add_argument("action", choices=["get", "set", "clear"])
    clipboard.add_argument("--text")
    clipboard.add_argument("--socket")
    clipboard.add_argument("--timeout-ms", type=int, default=2000)

    daemon = sub.add_parser("clipboard-daemon")
    daemon.add_argument("--socket", required=True)
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    x11 = None
    try:
        x11 = X11(args.display)
        if args.command == "windows":
            if args.limit < 1 or args.limit > 2000:
                raise ControlError("limit must be between 1 and 2000")
            payload = windows_command(x11, args)
        elif args.command == "resolve":
            payload = {
                "ok": True,
                "window": resolve_one(refresh_windows(x11)["windows"], args),
            }
        elif args.command == "activate":
            payload = activate_command(x11, args)
        elif args.command == "close":
            payload = close_command(x11, args)
        elif args.command == "window":
            if args.timeout_ms < 100 or args.timeout_ms > 15000:
                raise ControlError("timeout-ms must be between 100 and 15000")
            payload = window_command(x11, args)
        elif args.command == "wait":
            if args.timeout_ms < 1 or args.timeout_ms > 60000:
                raise ControlError("timeout-ms must be between 1 and 60000")
            payload = wait_command(x11, args)
        elif args.command == "clipboard":
            payload = clipboard_command(x11, args)
        elif args.command == "clipboard-daemon":
            clipboard_daemon(x11, args.socket)
            payload = {"ok": True}
        else:
            raise ControlError(f"unknown command: {args.command}")
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        return 0
    except Exception as error:
        print(json.dumps({
            "ok": False,
            "error": str(error),
        }, ensure_ascii=False, separators=(",", ":")))
        return 1
    finally:
        if x11 is not None:
            x11.close()


if __name__ == "__main__":
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    raise SystemExit(main())
