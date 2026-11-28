package com.jagdeep.autopilot.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dark-mode choice only; no Windows library is loaded. */
class DesktopTitleBarTest {
    private val ok = 0
    private val invalidArgument = 0x80070057.toInt()

    @Test
    fun `given Windows 10 20H1 or Windows 11 when dark mode is set then attribute 20 alone is used`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val applied = DesktopTitleBar.applyDarkMode(dark = true) { attribute, value -> calls += attribute to value; ok }
        assertTrue(applied)
        assertEquals(listOf(20 to 1), calls)
    }

    @Test
    fun `given Windows 10 before 20H1 when attribute 20 is rejected then attribute 19 is tried`() {
        val calls = mutableListOf<Int>()
        val applied = DesktopTitleBar.applyDarkMode(dark = false) { attribute, value ->
            calls += attribute
            assertEquals(0, value)
            if (attribute == 19) ok else invalidArgument
        }
        assertTrue(applied)
        assertEquals(listOf(20, 19), calls)
    }

    @Test
    fun `given Windows without dark mode when both attributes are rejected then nothing throws`() {
        assertFalse(DesktopTitleBar.applyDarkMode(dark = true) { _, _ -> invalidArgument })
    }
}
