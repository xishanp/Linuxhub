#!/usr/bin/env python3
"""Forward browser pointer and keyboard events into the local X11 display."""

import argparse
import ctypes
import ctypes.util
import json
import os
import sys
import time

DISPLAY = ctypes.c_void_p

libx11 = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
libxtst = ctypes.CDLL(ctypes.util.find_library("Xtst") or "libXtst.so.6")
libxfixes = ctypes.CDLL(ctypes.util.find_library("Xfixes") or "libXfixes.so.3")

libx11.XOpenDisplay.argtypes = [ctypes.c_char_p]
libx11.XOpenDisplay.restype = DISPLAY
libx11.XCloseDisplay.argtypes = [DISPLAY]
libx11.XDefaultScreen.argtypes = [DISPLAY]
libx11.XDefaultScreen.restype = ctypes.c_int
libx11.XDefaultRootWindow.argtypes = [DISPLAY]
libx11.XDefaultRootWindow.restype = ctypes.c_ulong
libx11.XStringToKeysym.argtypes = [ctypes.c_char_p]
libx11.XStringToKeysym.restype = ctypes.c_ulong
libx11.XKeysymToKeycode.argtypes = [DISPLAY, ctypes.c_ulong]
libx11.XKeysymToKeycode.restype = ctypes.c_ubyte
libx11.XFlush.argtypes = [DISPLAY]
libx11.XFlush.restype = ctypes.c_int

libxtst.XTestFakeMotionEvent.argtypes = [DISPLAY, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
libxtst.XTestFakeMotionEvent.restype = ctypes.c_int
libxtst.XTestFakeButtonEvent.argtypes = [DISPLAY, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
libxtst.XTestFakeButtonEvent.restype = ctypes.c_int
libxtst.XTestFakeKeyEvent.argtypes = [DISPLAY, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
libxtst.XTestFakeKeyEvent.restype = ctypes.c_int
libxfixes.XFixesHideCursor.argtypes = [DISPLAY, ctypes.c_ulong]
libxfixes.XFixesHideCursor.restype = ctypes.c_int
libxfixes.XFixesShowCursor.argtypes = [DISPLAY, ctypes.c_ulong]
libxfixes.XFixesShowCursor.restype = ctypes.c_int


def _keysym(name):
    value = libx11.XStringToKeysym(str(name).encode("utf-8"))
    if value:
        return int(value)
    return None


def _shifted_char(char):
    if 'A' <= char <= 'Z':
        return char
    shifted = {
        '!': 'exclam', '@': 'at', '#': 'numbersign', '$': 'dollar',
        '%': 'percent', '^': 'asciicircum', '&': 'ampersand', '*': 'asterisk',
        '(': 'parenleft', ')': 'parenright', '-': 'minus', '_': 'underscore',
        '=': 'equal', '+': 'plus', '[': 'bracketleft', '{': 'braceleft',
        ']': 'bracketright', '}': 'braceright', '\\': 'backslash', '|': 'bar',
        ';': 'semicolon', ':': 'colon', "'": 'apostrophe', '"': 'quotedbl',
        ',': 'comma', '<': 'less', '.': 'period', '>': 'greater',
        '/': 'slash', '?': 'question', '`': 'grave', '~': 'asciitilde',
        ' ': 'space',
    }.get(char, char)
    return shifted


class Input:
    def __init__(self, display_name):
        self.display = libx11.XOpenDisplay(display_name.encode("utf-8"))
        if not self.display:
            raise RuntimeError(f"cannot open X11 display {display_name}")
        self.screen = libx11.XDefaultScreen(self.display)
        self.root = libx11.XDefaultRootWindow(self.display)
        # The browser already draws the immediate local pointer. Hide the
        # cursor baked into the H.264 stream so the remote session has only one.
        libxfixes.XFixesHideCursor(self.display, self.root)
        self.flush()

    def close(self):
        if self.display:
            libxfixes.XFixesShowCursor(self.display, self.root)
            self.flush()
            libx11.XCloseDisplay(self.display)
            self.display = None

    def flush(self):
        libx11.XFlush(self.display)

    def move(self, x, y):
        libxtst.XTestFakeMotionEvent(self.display, self.screen, int(x), int(y), 0)
        self.flush()

    def button(self, button, pressed):
        libxtst.XTestFakeButtonEvent(self.display, int(button), 1 if pressed else 0, 0)
        self.flush()

    def key(self, name, pressed, shift=False):
        keysym = _keysym(name)
        if keysym is None:
            return
        keycode = int(libx11.XKeysymToKeycode(self.display, keysym))
        if not keycode:
            return
        if shift:
            shift_code = int(libx11.XKeysymToKeycode(self.display, _keysym("Shift_L") or 0))
            if shift_code:
                libxtst.XTestFakeKeyEvent(self.display, shift_code, 1, 0)
        libxtst.XTestFakeKeyEvent(self.display, keycode, 1 if pressed else 0, 0)
        if shift:
            shift_code = int(libx11.XKeysymToKeycode(self.display, _keysym("Shift_L") or 0))
            if shift_code:
                libxtst.XTestFakeKeyEvent(self.display, shift_code, 0, 0)
        self.flush()

    def text(self, text):
        for char in text:
            if char == '\n':
                self.key('Return', True)
                self.key('Return', False)
                continue
            name = _shifted_char(char)
            shifted = char.isupper() or (char in '~!@#$%^&*()_+{}|:"<>?')
            self.key(name, True, shifted)
            self.key(name, False, shifted)
            time.sleep(0.012)


KEY_NAMES = {
    'Enter': 'Return',
    'Escape': 'Escape',
    'Backspace': 'BackSpace',
    'Tab': 'Tab',
    'ArrowUp': 'Up',
    'ArrowDown': 'Down',
    'ArrowLeft': 'Left',
    'ArrowRight': 'Right',
    'Delete': 'Delete',
    'Home': 'Home',
    'End': 'End',
    'PageUp': 'Prior',
    'PageDown': 'Next',
    'Space': 'space',
    'Control': 'Control_L',
    'Shift': 'Shift_L',
    'Alt': 'Alt_L',
    'Meta': 'Super_L',
    'CapsLock': 'Caps_Lock',
    'Insert': 'Insert',
}


def handle(input_device, message):
    kind = message.get('type')
    if kind == 'move':
        input_device.move(message.get('x', 0), message.get('y', 0))
    elif kind == 'button':
        input_device.button(message.get('button', 1), bool(message.get('pressed')))
    elif kind == 'key':
        key = str(message.get('key', ''))
        key = KEY_NAMES.get(key, key)
        if len(key) == 1:
            key = _shifted_char(key)
        input_device.key(key, bool(message.get('pressed')), bool(message.get('shift')))
    elif kind == 'text':
        input_device.text(str(message.get('text', '')))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--display', default=os.environ.get('DISPLAY', ':0'))
    parser.add_argument('--daemon', action='store_true')
    args = parser.parse_args()
    device = Input(args.display)
    try:
        if not args.daemon:
            return 0
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                handle(device, json.loads(line))
            except Exception as error:
                print(f'doro-x11-input: {error}', file=sys.stderr, flush=True)
        return 0
    finally:
        device.close()


if __name__ == '__main__':
    raise SystemExit(main())
