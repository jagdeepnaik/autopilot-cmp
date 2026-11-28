package com.jagdeep.automation

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jagdeep.logger.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating controls drawn over other apps while the Accessibility service is on:
 *
 * - a **target** that marks where taps land; it never takes touches, so taps pass through it;
 * - a **handle** beside the target that drags it (hidden while a routine runs);
 * - a **panel** with Start/Stop and Pick, draggable by its grip.
 *
 * Create, show and change it on the main thread; [target], [moveTo] and the hit tests are safe
 * from any thread.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
internal class FloatingControls(
    private val service: AccessibilityService,
    private val onPress: (ControlPress) -> Unit,
) {
    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val targetSize = dp(36)
    private val handleSize = dp(44)
    private val handleGap = dp(12)
    private val cornerSize = dp(24)

    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    @Volatile private var active = false
    @Volatile private var shown = false

    /** Where taps land when a routine clicks "at the pointer". */
    @Volatile
    var target: ScreenPoint = ScreenPoint(0, 0)
        private set

    @Volatile private var handleRect = Rect()
    @Volatile private var panelRect = Rect()

    private val targetView = TargetView(service)
    private val handleView = HandleView(service)
    private val toggleButton = button("Start", START_COLOR)
    private val pickButton = button("Pick", PICK_COLOR)
    private val grip = TextView(service).apply {
        text = "• • •"
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textSize = 12f
        minHeight = dp(28)
        contentDescription = "Move these controls"
    }
    private val panel = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(8))
        background = rounded(PANEL_COLOR, dp(16).toFloat())
        addView(grip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(toggleButton, buttonParams())
        addView(pickButton, buttonParams())
    }

    private val targetParams = overlayParams(targetSize, targetSize, touchable = false)
    private val handleParams = overlayParams(handleSize, handleSize, touchable = true)
    private val panelParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)

    init {
        toggleButton.setOnClickListener { onPress(ControlPress.Toggle) }
        pickButton.setOnClickListener {
            onPress(ControlPress.Capture)
            targetView.animate().scaleX(1.5f).scaleY(1.5f).setDuration(90).withEndAction {
                targetView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
        toggleButton.contentDescription = "Start or stop the selected routine"
        pickButton.contentDescription = "Pick the spot under the target"
        handleView.contentDescription = "Drag to move the target"
        handleView.setOnTouchListener(DragListener { dx, dy, start -> moveTo(ScreenPoint(start.x + dx, start.y + dy)) }.from { target })
        grip.setOnTouchListener(DragListener { dx, dy, start ->
            panelParams.x = (start.x + dx).coerceIn(0, (screenWidth - panel.width).coerceAtLeast(0))
            panelParams.y = (start.y + dy).coerceIn(0, (screenHeight - panel.height).coerceAtLeast(0))
            update(panel, panelParams)
            rememberPanelRect()
        }.from { ScreenPoint(panelParams.x, panelParams.y) })
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rememberPanelRect() }
    }

    fun show() {
        if (shown) return
        measureScreen()
        target = ScreenPoint(screenWidth / 2, screenHeight / 2)
        panelParams.x = screenWidth - dp(96)
        panelParams.y = screenHeight / 3
        layoutTarget()
        try {
            windows.addView(targetView, targetParams)
            windows.addView(handleView, handleParams)
            windows.addView(panel, panelParams)
            shown = true
        } catch (e: Exception) {
            Log.w("Couldn't show the floating controls: ${e.message}", TAG)
        }
    }

    fun remove() {
        if (!shown) return
        shown = false
        for (view in listOf<View>(targetView, handleView, panel)) {
            try {
                windows.removeViewImmediate(view)
            } catch (_: Exception) {
                // Already gone with the service.
            }
        }
    }

    /** Offers Stop while a routine runs and Start otherwise; the handle and Pick only when idle. */
    fun setActive(isActive: Boolean) {
        active = isActive
        toggleButton.text = if (isActive) "Stop" else "Start"
        toggleButton.background = rounded(if (isActive) STOP_COLOR else START_COLOR, dp(12).toFloat())
        pickButton.visibility = if (isActive) View.GONE else View.VISIBLE
        handleView.visibility = if (isActive) View.GONE else View.VISIBLE
        // A hidden view still leaves its window in the way of taps, so the window lets them through.
        handleParams.flags = if (isActive) handleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        layoutTarget()
        update(handleView, handleParams)
    }

    fun setKeepAwake(awake: Boolean) {
        panelParams.flags = if (awake) panelParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else panelParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        update(panel, panelParams)
    }

    /** After a rotation or a display change, brings the target and panel back on screen. */
    fun keepOnScreen() {
        measureScreen()
        moveTo(target)
        panelParams.x = panelParams.x.coerceIn(0, (screenWidth - panel.width).coerceAtLeast(0))
        panelParams.y = panelParams.y.coerceIn(0, (screenHeight - panel.height).coerceAtLeast(0))
        update(panel, panelParams)
        rememberPanelRect()
    }

    /** Moves the target (and its handle) to [point], kept on screen. Safe from any thread. */
    fun moveTo(point: ScreenPoint) {
        target = ScreenPoint(
            point.x.coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
            point.y.coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
        )
        layoutTarget()
        main.post {
            update(targetView, targetParams)
            update(handleView, handleParams)
        }
    }

    fun isOverHandle(point: ScreenPoint): Boolean = handleRect.contains(point.x, point.y)

    /** True when a tap at [point] would land on the handle or the panel instead of the app below. */
    fun isOverControls(point: ScreenPoint): Boolean = isOverHandle(point) || panelRect.contains(point.x, point.y)

    fun isScreenCorner(point: ScreenPoint): Boolean {
        val nearLeft = point.x <= cornerSize
        val nearRight = point.x >= screenWidth - 1 - cornerSize
        val nearTop = point.y <= cornerSize
        val nearBottom = point.y >= screenHeight - 1 - cornerSize
        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    /** Places the target window around [target] and the handle diagonally beside it, on screen. */
    private fun layoutTarget() {
        val point = target
        targetParams.x = point.x - targetSize / 2
        targetParams.y = point.y - targetSize / 2
        val offset = targetSize / 2 + handleGap
        val right = point.x + offset + handleSize <= screenWidth
        val below = point.y + offset + handleSize <= screenHeight
        handleParams.x = if (right) point.x + offset else point.x - offset - handleSize
        handleParams.y = if (below) point.y + offset else point.y - offset - handleSize
        handleRect = if (active) Rect() else Rect(handleParams.x, handleParams.y, handleParams.x + handleSize, handleParams.y + handleSize)
    }

    private fun rememberPanelRect() {
        panelRect = Rect(panelParams.x, panelParams.y, panelParams.x + panel.width, panelParams.y + panel.height)
    }

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        if (!shown) return
        try {
            windows.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Removed between the post and now.
        }
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun button(label: String, color: Int) = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(72)
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(color, dp(12).toFloat())
        isClickable = true
        isFocusable = true
    }

    private fun buttonParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /** Reports how far a finger has moved since it went down, from where the dragged thing started. */
    private class DragListener(private val onDrag: (dx: Int, dy: Int, start: ScreenPoint) -> Unit) : View.OnTouchListener {
        private var startPosition: () -> ScreenPoint = { ScreenPoint(0, 0) }
        private var downX = 0f
        private var downY = 0f
        private var start = ScreenPoint(0, 0)

        fun from(position: () -> ScreenPoint) = apply { startPosition = position }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    start = startPosition()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (abs(dx) + abs(dy) > 0) onDrag(dx, dy, start)
                }
            }
            return true
        }
    }

    /** A ring with a centre dot, visible on light and dark apps alike. */
    private class TargetView(context: Context) : View(context) {
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 5 * context.resources.displayMetrics.density
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ACCENT_COLOR
            strokeWidth = 2.5f * context.resources.displayMetrics.density
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_COLOR }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - outline.strokeWidth
            canvas.drawCircle(cx, cy, radius, outline)
            canvas.drawCircle(cx, cy, radius, ring)
            canvas.drawCircle(cx, cy, ring.strokeWidth, dot)
        }
    }

    /** A round grip with a four-way arrow mark. */
    private class HandleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_COLOR }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2 * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, width / 2f, fill)
            val arm = width * 0.22f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, mark)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, mark)
        }
    }

    private companion object {
        const val TAG = "FloatingControls"
        val PANEL_COLOR = Color.argb(0xE0, 0x20, 0x21, 0x24)
        val START_COLOR = Color.rgb(0x2E, 0x7D, 0x32)
        val STOP_COLOR = Color.rgb(0xC6, 0x28, 0x28)
        val PICK_COLOR = Color.rgb(0x45, 0x5A, 0x64)
        val ACCENT_COLOR = Color.rgb(0xFF, 0x6D, 0x00)
    }
}
