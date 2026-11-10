package com.jagdeep.autopilot.presentation.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.ui.navigation.MainNavigationItem
import com.jagdeep.ui.navigation.RouteTopLevel
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopShellUiTest {
    private val items = linkedMapOf<NavKey, MainNavigationItem>(
        RouteTopLevel.Home to MainNavigationItem("Routines", Icons.Default.History),
        RouteTopLevel.Activity to MainNavigationItem("Activity", Icons.Default.History),
    )
    private val footerItems = linkedMapOf<NavKey, MainNavigationItem>(
        RouteTopLevel.Settings to MainNavigationItem("Settings", Icons.Default.Settings),
    )

    private fun ui(state: ShellUiState, onStart: () -> Unit = {}, onStop: () -> Unit = {}, onNavigate: (NavKey) -> Unit = {},
                   header: @androidx.compose.runtime.Composable () -> Unit = {
                       com.jagdeep.ui.components.AppBarTop("Home", RouteTopLevel.Home, {}, {}, {}, titleIcon = { Icon(Icons.Default.History, "Page icon", Modifier.size(18.dp)) })
                   }) =
        @androidx.compose.runtime.Composable {
            AppTheme(darkTheme = true) {
                DesktopShell(
                    state = state, onSelectRoutine = {}, onStart = onStart, onStop = onStop,
                    selectedRoute = RouteTopLevel.Home, navigationItems = items, footerItems = footerItems, onNavigate = onNavigate, now = { 1_000L },
                    appVersion = "1.0.0",
                    toolbarStartInset = 0.dp, toolbarDragArea = { it() }, topBar = header,
                ) { Text("Page content") }
            }
        }

    @Test
    fun `toolbar retains original icon and toggles sidebar`() = runDesktopComposeUiTest(1000,600) {
        setContent(ui(ShellUiState()))
        onNodeWithContentDescription("Expand sidebar from toolbar").performClick()
        onNodeWithText("Activity").assertExists()
        onNodeWithContentDescription("Collapse sidebar from toolbar").performClick()
        onNodeWithText("Activity").assertDoesNotExist()
    }

    @Test
    fun `given keyboard focus then Enter expands sidebar without drawing icon`() = runDesktopComposeUiTest(1000,600) {
        setContent(ui(ShellUiState()))
        onNodeWithContentDescription("Expand sidebar").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("Sidebar toggle icon", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Expand sidebar").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        onNodeWithText("Activity").assertExists()
    }

    @Test
    fun `given a page header then toolbar hosts its title back and actions above content`() = runDesktopComposeUiTest(1000,600) {
        var back = false
        var cleared = false
        setContent(ui(ShellUiState(), header = {
            com.jagdeep.ui.components.AppBarTop("Edit routine", com.jagdeep.home.ui.navigation.RouteRoutineEditor(), { back = true }, {}, {},
                actions = { TextButton(onClick = { cleared = true }) { Text("Clear") } })
        }))
        val titleBounds = onNodeWithText("Edit routine").getUnclippedBoundsInRoot()
        val contentBounds = onNodeWithText("Page content").getUnclippedBoundsInRoot()
        assertTrue(titleBounds.top < DesktopToolbarHeight)
        assertTrue(contentBounds.top >= DesktopToolbarHeight)
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Clear").performClick()
        assertTrue(back); assertTrue(cleared)
    }

    @Test
    fun `given empty sidebar area then hover draws no icon and only click changes width`() = runDesktopComposeUiTest(1000,600) {
        setContent(ui(ShellUiState()))
        onNodeWithTag("Sidebar toggle icon", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("Sidebar toggle area").performMouseInput { enter(center) }
        onNodeWithTag("Sidebar toggle icon", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Activity").assertDoesNotExist()
        onNodeWithContentDescription("Expand sidebar").performClick()
        onNodeWithText("Activity").assertExists()
        onNodeWithContentDescription("Collapse sidebar").performClick()
        onNodeWithText("Activity").assertDoesNotExist()
    }

    @Test
    fun `given a selected routine when idle then the toolbar offers Start, not Stop, and the strip navigates`() = runDesktopComposeUiTest(1000, 600) {
        var started = false
        var navigatedTo: NavKey? = null
        val routine = ClickRoutine("c", "Refresh")
        setContent(ui(ShellUiState(routines = listOf(routine), selected = routine, chromeConnected = true), onStart = { started = true }, onNavigate = { navigatedTo = it }))
        onNodeWithText("Refresh").assertExists()
        onNodeWithText("Chrome connected").assertExists()
        onNodeWithText("Page content").assertExists()
        onNodeWithContentDescription("Stop").assertIsNotEnabled()
        onNodeWithContentDescription("Start Refresh").assertIsEnabled().performClick()
        assertEquals(true, started)
        onNodeWithContentDescription("Settings").performClick()
        assertEquals<NavKey?>(RouteTopLevel.Settings, navigatedTo, "Settings opens from the foot of the strip")
        onNodeWithContentDescription("Activity").performClick()
        assertEquals<NavKey?>(RouteTopLevel.Activity, navigatedTo)
    }

    @Test
    fun `given the page strip when widened then it shows the pages' names, and narrows again`() = runDesktopComposeUiTest(1000, 600) {
        setContent(ui(ShellUiState()))
        onNodeWithText("Activity").assertDoesNotExist()
        onNodeWithContentDescription("Expand sidebar").performClick()
        onNodeWithText("Activity").assertExists()
        onNodeWithContentDescription("Collapse sidebar").performClick()
        waitForIdle()
        onNodeWithText("Activity").assertDoesNotExist()
    }

    @Test
    fun `given a routine running when shown then Stop is offered and the status says what's running`() = runDesktopComposeUiTest(1000, 600) {
        var stopped = false
        val routine = ClickRoutine("c", "Refresh")
        val state = ShellUiState(
            routines = listOf(routine), selected = routine, isActive = true, runningName = "Refresh",
            operation = "Refresh · 3 clicks", nextClickAtMillis = 2_500,
        )
        setContent(ui(state, onStop = { stopped = true }))
        onNodeWithText("Running Refresh").assertExists()
        onNodeWithText("Refresh · 3 clicks · next click in 1.5 s").assertExists()
        onNodeWithContentDescription("Start Refresh").assertIsNotEnabled()
        onNodeWithContentDescription("Stop").performClick()
        assertEquals(true, stopped)
    }
}
