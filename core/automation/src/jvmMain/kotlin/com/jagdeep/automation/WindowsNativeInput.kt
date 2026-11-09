package com.jagdeep.automation

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/** Windows needs no permission for input, except that it can't reach apps running as administrator. */
internal class WindowsNativeInput : NativeInput {
    private val displayAwake = ThreadDisplayAwake { flags -> Kernel32.INSTANCE.SetThreadExecutionState(flags) != 0 }

    override fun access(): InputAccess = InputAccess.Granted

    override fun requestAccess(): Boolean = true

    /** The high bit of GetAsyncKeyState is set while the key is down. */
    override fun isKeyDown(key: HotKey): Boolean =
        User32.INSTANCE.GetAsyncKeyState(VK_F1 + key.ordinal).toInt() and KEY_DOWN_BIT != 0

    override fun frontmost(): String? = User32.INSTANCE.GetForegroundWindow()?.let(::appNameOf)

    override fun running(): List<String> {
        val names = linkedSetOf<String>()
        User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { window, _ ->
            if (User32.INSTANCE.IsWindowVisible(window) && User32.INSTANCE.GetWindowTextLength(window) > 0) {
                appNameOf(window)?.let(names::add)
            }
            true
        }, null)
        return names.toList()
    }

    /**
     * Windows lets the app in front hand over the front spot, which holds here because Start was
     * just pressed in this app. Windows are listed front to back, so the most recent one is used.
     */
    override fun bringToFront(name: String) {
        var target: HWND? = null
        User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { window, _ ->
            val matches = User32.INSTANCE.IsWindowVisible(window) && User32.INSTANCE.GetWindowTextLength(window) > 0 &&
                appNameOf(window)?.let { isExecutableOfApp(it, name) } == true
            if (matches) target = window
            !matches
        }, null)
        val window = target ?: return
        // Only a minimized window is restored: restoring a maximized one would shrink it.
        if (User32.INSTANCE.GetWindowLong(window, WinUser.GWL_STYLE) and WS_MINIMIZE != 0) {
            User32.INSTANCE.ShowWindow(window, WinUser.SW_RESTORE)
        }
        User32.INSTANCE.SetForegroundWindow(window)
    }

    override fun setDisplayAwake(awake: Boolean) = displayAwake.setAwake(awake)

    private fun appNameOf(window: HWND): String? {
        val processId = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(window, processId)
        val process = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, processId.value) ?: return null
        return try {
            val buffer = CharArray(MAX_PATH_CHARS)
            val length = IntByReference(buffer.size)
            if (Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, length)) {
                appNameFromExecutablePath(Native.toString(buffer))
            } else {
                null
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    private companion object {
        const val VK_F1 = 0x70
        const val KEY_DOWN_BIT = 0x8000
        const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        const val MAX_PATH_CHARS = 1024
        const val WS_MINIMIZE = 0x20000000
    }
}

/** `chrome` is the executable of the app called `Google Chrome`: its last word, in any case. */
internal fun isExecutableOfApp(executable: String, appName: String): Boolean =
    executable.equals(appName.trim().substringAfterLast(' '), ignoreCase = true)

/** `C:\Program Files\Google\Chrome\Application\chrome.exe` is shown as `chrome`. */
internal fun appNameFromExecutablePath(path: String): String? =
    path.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe").removeSuffix(".EXE").takeIf { it.isNotBlank() }
