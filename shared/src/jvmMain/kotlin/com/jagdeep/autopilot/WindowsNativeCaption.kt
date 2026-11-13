package com.jagdeep.autopilot

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.jagdeep.logger.Log
import com.sun.jna.CallbackReference
import java.util.concurrent.ConcurrentHashMap
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Lets Windows recognize our custom maximize control and empty caption space.
 * Child render windows yield only these regions to the top-level window. All other
 * input continues through the original AWT procedure, including the clickable app icon.
 */
internal class WindowsNativeCaption(
    private val window: ComposeWindow,
    private val toggleMaximize: () -> Unit,
) : AutoCloseable {
    @Volatile var maximizeBounds: Rect = Rect.Zero
    @Volatile var dragBounds: Rect = Rect.Zero
    @Volatile private var owner: HWND? = null
    private data class Hook(val hwnd: HWND, val original: Pointer)
    private val hooked = ConcurrentHashMap<Long, Hook>()
    private var oldStyle: Int? = null
    private var pressed = false
    private val coordinates by lazy { Native.load("user32", CoordinatesApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    private val user get() = User32.INSTANCE
    // Strong reference retained until every subclass has been removed.
    private val procedure = object : SubclassProc {
        override fun invoke(hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
            val root = owner
            if (root != null) {
                if (hwnd == root && message == WM_NCCALCSIZE && wParam.toInt() != 0) {
                    // Keep the custom toolbar in the client area instead of adding a second title row.
                    // A maximized thick frame extends outside the work area; retain that invisible inset.
                    if (coordinates.IsZoomed(root)) {
                        val dpi = coordinates.GetDpiForWindow(root)
                        val border = coordinates.GetSystemMetricsForDpi(32, dpi) + coordinates.GetSystemMetricsForDpi(92, dpi)
                        val rect = Pointer(lParam.toLong())
                        rect.setInt(0, rect.getInt(0) + border)
                        rect.setInt(4, rect.getInt(4) + border)
                        rect.setInt(8, rect.getInt(8) - border)
                        rect.setInt(12, rect.getInt(12) - border)
                    }
                    return LRESULT(0)
                }
                if (message == WM_NCHITTEST) {
                    val origin = POINT()
                    if (coordinates.ClientToScreen(root, origin)) {
                        val x = lParam.toLong().toShort().toInt() - origin.x
                        val y = (lParam.toLong() shr 16).toShort().toInt() - origin.y
                        val client = RECT()
                        user.GetClientRect(root, client)
                        val border = 6f * coordinates.GetDpiForWindow(root) / 96f
                        val edge = if (coordinates.IsZoomed(root)) null else resizeHitTest(client.right.toFloat(), client.bottom.toFloat(), border, x.toFloat(), y.toFloat())
                        val hit = edge ?: captionHitTest(maximizeBounds, dragBounds, x.toFloat(), y.toFloat())
                        if (hit != null) return LRESULT(if (hwnd == root) hit.toLong() else HTTRANSPARENT.toLong())
                    }
                }
                if (hwnd == root) {
                    if (message == WM_NCLBUTTONDOWN && wParam.toInt() == HTMAXBUTTON) {
                        pressed = true
                        return LRESULT(0)
                    }
                    if (message == WM_NCLBUTTONUP) {
                        val activate = pressed && wParam.toInt() == HTMAXBUTTON
                        pressed = false
                        if (activate) { SwingUtilities.invokeLater(toggleMaximize); return LRESULT(0) }
                    }
                    if (message == WM_CANCELMODE || message == WM_CAPTURECHANGED) pressed = false
                    // Windows owns caption dragging, double-click and the Snap Layouts flyout.
                    if ((message == WM_NCMOUSEMOVE && wParam.toInt() == HTMAXBUTTON) ||
                        (message == WM_NCLBUTTONDBLCLK && wParam.toInt() == HTCAPTION) ||
                        (message == WM_NCLBUTTONDOWN && (wParam.toInt() == HTCAPTION || wParam.toInt() in 10..17))) {
                        return user.DefWindowProc(hwnd, message, wParam, lParam)
                    }
                }
            }
            val hook = hooked[Pointer.nativeValue(hwnd.pointer)]
            val result = if (hook != null) user.CallWindowProc(hook.original, hwnd, message, wParam, lParam)
                else user.DefWindowProc(hwnd, message, wParam, lParam)
            if (message == 0x82) hooked.remove(Pointer.nativeValue(hwnd.pointer)) // WM_NCDESTROY
            return result
        }
    }
    private val timer = Timer(500) { attachWindows() }

    fun install() {
        check(SwingUtilities.isEventDispatchThread())
        attachWindows()
        timer.start()
    }

    private fun attachWindows() {
        if (!window.isDisplayable) return
        runCatching {
            val root = HWND(Native.getComponentPointer(window) ?: return)
            owner = root
            attach(root)
            if (oldStyle == null) {
                val style = user.GetWindowLong(root, WinUser.GWL_STYLE)
                oldStyle = style
                // Snap Layouts require a native resizable caption, not just HTMAXBUTTON.
                // WM_NCCALCSIZE above removes the native title row without removing its capabilities.
                user.SetWindowLong(root, WinUser.GWL_STYLE, (style and WinUser.WS_POPUP.inv()) or WinUser.WS_OVERLAPPEDWINDOW)
                user.SetWindowPos(root, null, 0, 0, 0, 0, 0x37) // FRAMECHANGED | NOMOVE | NOSIZE | NOZORDER | NOACTIVATE
            }
            user.EnumChildWindows(root, { hwnd, _ -> attach(hwnd); true }, Pointer.NULL)
        }.onFailure { timer.stop(); Log.w("Windows caption integration unavailable: ${it.javaClass.simpleName}", "WindowsNativeCaption") }
    }

    private fun attach(hwnd: HWND) {
        val key = Pointer.nativeValue(hwnd.pointer)
        if (hooked.containsKey(key)) return
        val original = user.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC).toPointer()
        val hook = Hook(hwnd, original)
        hooked[key] = hook
        Native.setLastError(0)
        val previous = user.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, CallbackReference.getFunctionPointer(procedure))
        if (previous == null && Native.getLastError() != 0) {
            hooked.remove(key)
            error("Could not register Windows caption procedure")
        }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        timer.stop()
        hooked.values.toList().reversed().forEach { hook ->
            if (user.IsWindow(hook.hwnd)) user.SetWindowLongPtr(hook.hwnd, WinUser.GWL_WNDPROC, hook.original)
        }
        owner?.let { hwnd -> oldStyle?.let { if (user.IsWindow(hwnd)) user.SetWindowLong(hwnd, WinUser.GWL_STYLE, it) } }
        hooked.clear()
        owner = null
    }

    private interface CoordinatesApi : StdCallLibrary {
        fun ClientToScreen(hwnd: HWND, point: POINT): Boolean
        fun IsZoomed(hwnd: HWND): Boolean
        fun GetDpiForWindow(hwnd: HWND): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    }
    private interface SubclassProc : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }
    companion object {
        internal const val HTCAPTION = 2
        internal const val HTMAXBUTTON = 9
        private const val HTTRANSPARENT = -1
        private const val WM_NCCALCSIZE = 0x83
        private const val WM_NCHITTEST = 0x84
        private const val WM_NCMOUSEMOVE = 0xA0
        private const val WM_NCLBUTTONDOWN = 0xA1
        private const val WM_NCLBUTTONUP = 0xA2
        private const val WM_NCLBUTTONDBLCLK = 0xA3
        private const val WM_CANCELMODE = 0x1F
        private const val WM_CAPTURECHANGED = 0x215

        internal fun resizeHitTest(width: Float, height: Float, border: Float, x: Float, y: Float): Int? {
            if (x < 0 || y < 0 || x >= width || y >= height) return null
            val left = x < border
            val right = x >= width - border
            val top = y < border
            val bottom = y >= height - border
            return when {
                top && left -> 13
                top && right -> 14
                bottom && left -> 16
                bottom && right -> 17
                left -> 10
                right -> 11
                top -> 12
                bottom -> 15
                else -> null
            }
        }

        internal fun captionHitTest(maximize: Rect, drag: Rect, x: Float, y: Float): Int? = when {
            maximize.contains(Offset(x, y)) -> HTMAXBUTTON
            drag.contains(Offset(x, y)) -> HTCAPTION
            else -> null
        }
    }
}
