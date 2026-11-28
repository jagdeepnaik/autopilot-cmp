package com.jagdeep.autopilot.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jagdeep.logger.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference

/**
 * Fits the window to the Android Studio-like shell. macOS: as in Android Studio, the app's toolbar
 * shares the title bar's row with the system's own window buttons, so the bar is see-through, the
 * content runs under it, and the window name gives way to the toolbar. Windows: the app supplies a single toolbar row with caption controls in an undecorated,
 * resizable Compose window. Linux keeps its system bar.
 */
internal object DesktopTitleBar {
    private val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.contains("Mac", ignoreCase = true)
    val isWindows = osName.contains("Windows", ignoreCase = true)

    /** Room the Mac window buttons take at the start of the toolbar's row. */
    val toolbarStartInset: Dp = if (isMac) 72.dp else 0.dp

    /** Call once, before the window is shown. */
    fun prepare(window: ComposeWindow) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    /** Colours the window behind the shell, and the Windows title bar, to match the theme. */
    fun apply(window: ComposeWindow, dark: Boolean, background: Color, content: Color) {
        when {
            isMac -> window.background = java.awt.Color(background.toArgb())
            isWindows -> runCatching {
                val hwnd = HWND(Native.getComponentPointer(window) ?: return)
                applyDarkMode(dark) { attribute, value -> setAttribute(hwnd, attribute, value) }
                // Windows 11 only: Windows 10 returns an error for these, which leaves its default colours.
                setAttribute(hwnd, DWMWA_CAPTION_COLOR, background.toColorRef())
                setAttribute(hwnd, DWMWA_TEXT_COLOR, content.toColorRef())
            }.onFailure { Log.w("Title bar colours not applied: ${it.message}", TAG) }
        }
    }

    /**
     * Dark mode is attribute 20 from Windows 10 20H1 (build 19041) and on Windows 11, and was 19 on
     * Windows 10 1809 to 1909. The first one Windows accepts wins; if neither is, the frame stays light.
     */
    internal fun applyDarkMode(dark: Boolean, set: (attribute: Int, value: Int) -> Int): Boolean =
        DARK_MODE_ATTRIBUTES.any { attribute -> set(attribute, if (dark) 1 else 0) == S_OK }

    /** Returns the HRESULT. Older Windows rejects attributes it doesn't know; that only means the default title bar. */
    private fun setAttribute(hwnd: HWND, attribute: Int, value: Int): Int =
        Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value).pointer, Int.SIZE_BYTES)

    /** COLORREF is 0x00BBGGRR. */
    private fun Color.toColorRef(): Int {
        val argb = toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (blue shl 16) or (green shl 8) or red
    }

    @Suppress("FunctionName")
    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
        }
    }

    private const val TAG = "DesktopTitleBar"
    private const val S_OK = 0
    internal val DARK_MODE_ATTRIBUTES = listOf(20, 19) // DWMWA_USE_IMMERSIVE_DARK_MODE, then its pre-20H1 value
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
}
