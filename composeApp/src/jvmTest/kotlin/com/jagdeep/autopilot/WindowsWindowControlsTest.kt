package com.jagdeep.autopilot

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
