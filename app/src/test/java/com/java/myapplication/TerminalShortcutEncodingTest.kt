package com.java.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalShortcutEncodingTest {
    @Test fun shortcutSequencesMatchTerminalProtocol() {
        assertEquals("\u001B", KEY_ESC)
        assertEquals("\t", KEY_TAB)
        assertEquals("\u001B[5~", KEY_PG_UP)
        assertEquals("\u001B[6~", KEY_PG_DOWN)
        assertEquals("\u001B[H", KEY_HOME)
        assertEquals("\u001B[F", KEY_END)
        assertEquals("\u001B[A", KEY_UP)
        assertEquals("\u001B[B", KEY_DOWN)
        assertEquals("\u001B[D", KEY_LEFT)
        assertEquals("\u001B[C", KEY_RIGHT)
    }

    @Test fun ctrlLettersBecomeAsciiControlCodes() {
        assertEquals("\u0001", encodeTerminalText("a", ctrl = true, alt = false))
        assertEquals("\u001A", encodeTerminalText("Z", ctrl = true, alt = false))
    }

    @Test fun ctrlLeavesNonAsciiLettersUnchanged() {
        assertEquals("1", encodeTerminalText("1", ctrl = true, alt = false))
        assertEquals("中", encodeTerminalText("中", ctrl = true, alt = false))
        assertEquals("ab", encodeTerminalText("ab", ctrl = true, alt = false))
    }

    @Test fun altPrefixesEscapeAndCombinesWithCtrl() {
        assertEquals("\u001Bx", encodeTerminalText("x", ctrl = false, alt = true))
        assertEquals("\u0003", encodeTerminalText("c", ctrl = true, alt = false))
        assertEquals("\u001B\u0003", encodeTerminalText("c", ctrl = true, alt = true))
    }

    @Test fun emptyTextStaysEmpty() {
        assertEquals("", encodeTerminalText("", ctrl = true, alt = true))
    }
}
