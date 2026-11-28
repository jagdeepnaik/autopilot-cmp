package com.jagdeep.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jagdeep.logger.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Automation through [InputAccessibilityService]. The floating target stands in for the mouse
 * pointer: a click with no point taps where the target is, and a click at a point moves the target
 * there first, as the mouse moves on desktop. The floating Start/Stop and Pick buttons stand in for
 * the hot keys.
 */
internal object AndroidDesktopAutomation : DesktopAutomation {
    private const val TAG = "AndroidAutomation"
    private const val TAP_MILLIS = 40L
    private const val LONG_PRESS_MILLIS = 650L
    private const val BETWEEN_TAPS_MILLIS = 70L
    private const val GESTURE_TIMEOUT_MILLIS = 3_000L
    private const val HANDLE_MOVE_SETTLE_MILLIS = 120L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var service: InputAccessibilityService? = null

    @Volatile
    private var controls: FloatingControls? = null

    @Volatile
    private var frontPackage: String? = null

    @Volatile
    private var routineActive = false

    @Volatile
    private var keepAwake = false

    private val presses = MutableSharedFlow<ControlPress>(extraBufferCapacity = 8)
    private val acceptedWindows = ConcurrentHashMap<String, Boolean>()
    private val labels = ConcurrentHashMap<String, String>()

    private val context: Context? get() = service ?: AndroidAutomation.appContext

    // Service lifecycle, called on the main thread.

    fun attach(connected: InputAccessibilityService) {
        service = connected
        controls?.remove()
        controls = FloatingControls(connected, ::onControlPress).also { floating ->
            floating.show()
            floating.setActive(routineActive)
            floating.setKeepAwake(keepAwake)
        }
        Log.print("Accessibility service connected", TAG)
    }

    fun detach(disconnected: InputAccessibilityService) {
        if (service !== disconnected) return
        controls?.remove()
        controls = null
        service = null
        Log.print("Accessibility service disconnected", TAG)
    }

    fun onScreenChanged() {
        controls?.keepOnScreen()
    }

    fun onWindowChanged(packageName: String?, className: String?) {
        if (packageName.isNullOrEmpty()) return
        if (isAppWindow(packageName, className)) frontPackage = packageName
    }

    private fun onControlPress(press: ControlPress) {
        // Nobody is listening when the system restarted only the service: open the app instead.
        if (presses.subscriptionCount.value == 0) {
            openApp()
            return
        }
        presses.tryEmit(press)
    }

    // DesktopAutomation

    override val usesFloatingControls: Boolean get() = true

    override fun showRoutineActive(active: Boolean) {
        routineActive = active
        main.post { controls?.setActive(active) }
    }

    override val pointer: PointerDevice = object : PointerDevice {
        override fun position(): ScreenPoint? = controls?.target

        override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
            val floating = controls ?: return false
            val point = at ?: floating.target
            if (at != null) {
                // Moving the target also moves its handle; let that land before tapping where it was.
                val wasUnderHandle = floating.isOverHandle(point)
                floating.moveTo(point)
                if (wasUnderHandle) Thread.sleep(HANDLE_MOVE_SETTLE_MILLIS)
            }
            val press = if (button == PointerButton.Right) LONG_PRESS_MILLIS else TAP_MILLIS
            repeat(count.coerceAtLeast(1)) { index ->
                if (index > 0) Thread.sleep(BETWEEN_TAPS_MILLIS)
                if (!tap(point, press)) return false
            }
            return true
        }

        override fun isScreenCorner(point: ScreenPoint): Boolean = controls?.isScreenCorner(point) == true

        override fun isOverOwnFocusedWindow(point: ScreenPoint): Boolean {
            val own = AndroidAutomation.appContext?.packageName ?: service?.packageName
            return (own != null && frontPackage == own) || controls?.isOverControls(point) == true
        }
    }

    override val access: InputAccessGate = object : InputAccessGate {
        override fun access(): InputAccess = if (service != null) InputAccess.Granted else InputAccess.NeedsPermission

        override fun requestAccess(): Boolean {
            val context = context ?: return false
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, AndroidAutomation.serviceClass).flattenToString())
            val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            for (intent in listOf(details, list)) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: Exception) {
                    // Not every device has the details page; fall back to the list.
                }
            }
            return false
        }
    }

    override val hotKeys: HotKeyMonitor = object : HotKeyMonitor {
        override val presses: Flow<HotKey> = emptyFlow()
        override val controls: Flow<ControlPress> = this@AndroidDesktopAutomation.presses.asSharedFlow()
    }

    override val apps: ForegroundApps = object : ForegroundApps {
        override fun frontmost(): String? = frontPackage?.let(::labelOf)

        override fun running(): List<String> {
            val context = context ?: return emptyList()
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return try {
                context.packageManager.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName }
                    .filter { it != context.packageName }
                    .distinct()
                    .map(::labelOf)
                    .distinct()
                    .sortedBy { it.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun playAlert() {
        val context = context ?: return
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.w("Alert sound failed: ${e.message}", TAG)
        }
    }

    override fun setDisplayAwake(awake: Boolean) {
        keepAwake = awake
        main.post { controls?.setKeepAwake(awake) }
    }

    override fun close() = Unit

    // Helpers

    /** Sends one press at [point] and waits for the system to finish it. */
    private fun tap(point: ScreenPoint, durationMillis: Long): Boolean {
        val connected = service ?: return false
        val path = Path().apply { moveTo(point.x.coerceAtLeast(0).toFloat(), point.y.coerceAtLeast(0).toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
            .build()
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Waiting here would block the callback; report whether the system took the gesture.
            return connected.dispatchGesture(gesture, callback, main)
        }
        main.post { if (!connected.dispatchGesture(gesture, callback, main)) done.countDown() }
        if (!done.await(GESTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return false
        return completed.get()
    }

    /**
     * Whether a window change means [packageName] is now the app in front. Keyboards and system
     * panels also change windows but aren't apps; this app's own floating controls aren't either.
     */
    private fun isAppWindow(packageName: String, className: String?): Boolean {
        val context = context ?: return false
        if (packageName == context.packageName) {
            // Only an activity of this app counts, never its floating controls.
            return className != null && try {
                context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        return acceptedWindows.getOrPut(packageName) {
            val pm = context.packageManager
            pm.getLaunchIntentForPackage(packageName) != null || packageName in homePackages(context)
        }
    }

    private fun homePackages(context: Context): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        val context = context ?: return packageName
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun openApp() {
        val context = context ?: return
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            try {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w("Couldn't open the app: ${e.message}", TAG)
            }
        }
    }
}
