package com.jagdeep.autopilot.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Observes unhandled caption taps without consuming input needed for window dragging.
 * Buttons consume their gestures, so double-clicking Home or a routine control never zooms.
 * Windows native non-client regions handle their own double-click before Compose sees it.
 */
internal fun Modifier.captionDoubleClick(onDoubleClick: () -> Unit): Modifier = pointerInput(onDoubleClick) {
    var previousUp: Long? = null
    var previousPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var valid = !down.isConsumed && currentEvent.buttons.isPrimaryPressed
        var up = down
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            valid = valid && !change.isConsumed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
            up = change
        } while (up.pressed)
        if (!valid || up.pressed) {
            previousUp = null
        } else {
            val elapsed = previousUp?.let { down.uptimeMillis - it }
            if (elapsed != null && elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - previousPosition).getDistance() <= viewConfiguration.touchSlop) {
                previousUp = null
                onDoubleClick()
            } else {
                previousUp = up.uptimeMillis
                previousPosition = up.position
            }
        }
    }
}
