package com.jagdeep.autopilot

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowsWindowControlsTest {
    @Test
    fun `given custom frame then resize edges preserve all eight native directions`() {
        val points = listOf(1f to 1f, 799f to 1f, 1f to 599f, 799f to 599f, 1f to 300f, 799f to 300f, 400f to 1f, 400f to 599f)
        val hits = points.map { (x,y) -> WindowsNativeCaption.resizeHitTest(800f, 600f, 6f, x, y) }
        assertEquals(listOf(13, 14, 16, 17, 10, 11, 12, 15), hits)
        kotlin.test.assertNull(WindowsNativeCaption.resizeHitTest(800f, 600f, 6f, 400f, 300f))
        kotlin.test.assertNull(WindowsNativeCaption.resizeHitTest(800f, 600f, 6f, -5f, 0f))
    }

    @Test
    fun `given caption background then double click zooms while controls remain clickable`() = runDesktopComposeUiTest(800, 600) {
        var zooms = 0
        var homes = 0
        setContent {
            AppTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(Modifier.height(38.dp).then(Modifier.captionDoubleClick { zooms++ }).testTag("caption")) {
                    Row {
                        WindowsWindowIdentity { homes++ }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(300.dp).height(38.dp).testTag("blank"))
                    }
                }
            }
        }
        onNodeWithTag("blank").performMouseInput { doubleClick() }
        assertEquals(1, zooms)
        onNodeWithContentDescription("AutoPilot window icon").performMouseInput { doubleClick() }
        assertEquals(1, zooms)
        assertEquals(2, homes)
    }

    @Test
    fun `given Windows title icon when clicked then home callback is invoked`() = runDesktopComposeUiTest(800, 600) {
        var homes = 0
        setContent { AppTheme(darkTheme = false) { WindowsWindowIdentity { homes++ } } }
        onNodeWithContentDescription("AutoPilot window icon").performClick()
        assertEquals(1, homes)
    }

    @Test
    fun `given caption rectangles then only empty drag space and maximize control are native`() {
        val maximize = androidx.compose.ui.geometry.Rect(708f, 0f, 754f, 38f)
        val drag = androidx.compose.ui.geometry.Rect(600f, 0f, 662f, 38f)
        assertEquals(WindowsNativeCaption.HTMAXBUTTON, WindowsNativeCaption.captionHitTest(maximize, drag, 730f, 20f))
        assertEquals(WindowsNativeCaption.HTCAPTION, WindowsNativeCaption.captionHitTest(maximize, drag, 620f, 20f))
        for ((x,y) in listOf(20f to 20f, 100f to 20f, 770f to 20f, 620f to 100f, -10f to 10f)) {
            kotlin.test.assertNull(WindowsNativeCaption.captionHitTest(maximize, drag, x, y))
        }
    }

    @Test
    fun `given window captions then actions share one row and invoke their callbacks`() = runDesktopComposeUiTest(1000,600) {
        val calls = mutableListOf<String>()
        setContent {
            AppTheme(darkTheme = false) {
                Row(Modifier.height(38.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Routine toolbar")
                    WindowsWindowControls(false, { calls += "minimize" }, { calls += "maximize" }, { calls += "close" })
                }
            }
        }
        val labels = listOf("Minimize window", "Maximize window", "Close window")
        val bounds = labels.map { onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
        assertTrue(bounds.all { it.top == bounds.first().top && it.bottom == bounds.first().bottom })
        labels.forEach { onNodeWithContentDescription(it).performClick() }
        assertEquals(listOf("minimize", "maximize", "close"), calls)
    }

    @Test
    fun `given maximized window then caption offers restore`() = runDesktopComposeUiTest(1000,600) {
        setContent { AppTheme(darkTheme = true) { WindowsWindowControls(true, {}, {}, {}) } }
        onNodeWithContentDescription("Restore window").assertExists()
        onNodeWithContentDescription("Maximize window").assertDoesNotExist()
    }
}
