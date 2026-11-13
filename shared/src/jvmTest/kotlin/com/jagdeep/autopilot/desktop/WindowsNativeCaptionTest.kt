package com.jagdeep.autopilot.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.EventQueue
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Runs against real HWNDs on Windows; never loads Windows libraries on other platforms. */
class WindowsNativeCaptionTest {
    private interface Coordinates : StdCallLibrary {
        fun ClientToScreen(hwnd: HWND, point: POINT): Boolean
        fun IsZoomed(hwnd: HWND): Boolean
    }

    @Test
    fun `given custom Windows caption then native maximize hit and caption double click work`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val api = Native.load("user32", Coordinates::class.java, W32APIOptions.DEFAULT_OPTIONS)
        var window: ComposeWindow? = null
        var caption: WindowsNativeCaption? = null
        var toggles = 0
        try {
            EventQueue.invokeAndWait {
                val w = ComposeWindow()
                window = w
                w.isUndecorated = true
                w.setSize(800, 600)
                w.setLocation(40, 40)
                w.isVisible = true
                val c = WindowsNativeCaption(w) { toggles++ }
                caption = c
                c.maximizeBounds = Rect(708f, 0f, 754f, 38f)
                c.dragBounds = Rect(600f, 0f, 662f, 38f)
                c.install()
                val hwnd = HWND(Native.getComponentPointer(w))
                val style = User32.INSTANCE.GetWindowLong(hwnd, com.sun.jna.platform.win32.WinUser.GWL_STYLE)
                assertTrue(style and com.sun.jna.platform.win32.WinUser.WS_THICKFRAME != 0, "Snap requires a resizable native frame")
                assertTrue(style and com.sun.jna.platform.win32.WinUser.WS_CAPTION != 0, "Native caption capability must remain available")
                fun point(x: Int, y: Int): LPARAM {
                    val origin = POINT()
                    assertTrue(api.ClientToScreen(hwnd, origin))
                    return LPARAM((((origin.y + y).toLong() and 0xffff) shl 16) or ((origin.x + x).toLong() and 0xffff))
                }
                val maxPoint = point(730, 20)
                val dragPoint = point(620, 20)
                assertEquals(9, User32.INSTANCE.SendMessage(hwnd, 0x84, WPARAM(0), maxPoint).toInt())
                assertEquals(2, User32.INSTANCE.SendMessage(hwnd, 0x84, WPARAM(0), dragPoint).toInt())
                assertTrue(User32.INSTANCE.SendMessage(hwnd, 0x84, WPARAM(0), point(20, 100)).toInt() != 2)
                User32.INSTANCE.SendMessage(hwnd, 0xA3, WPARAM(2), dragPoint)
                assertTrue(api.IsZoomed(hwnd), "Native title-bar double-click must maximize")
                User32.INSTANCE.SendMessage(hwnd, 0xA3, WPARAM(2), dragPoint)
                assertTrue(!api.IsZoomed(hwnd), "Second double-click must restore")
                User32.INSTANCE.SendMessage(hwnd, 0xA1, WPARAM(9), maxPoint)
                User32.INSTANCE.SendMessage(hwnd, 0xA2, WPARAM(9), maxPoint)
            }
            SwingUtilities.invokeAndWait { assertEquals(1, toggles) }
        } finally {
            EventQueue.invokeAndWait { caption?.close(); window?.dispose() }
        }
    }
}
