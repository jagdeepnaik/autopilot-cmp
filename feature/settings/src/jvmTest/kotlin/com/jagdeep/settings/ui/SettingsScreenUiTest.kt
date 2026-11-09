package com.jagdeep.settings.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {
    private val gate = object : InputAccessGate {
        var current = InputAccess.Granted
        override fun access() = current
        override fun requestAccess() = true
    }

    @Test
    fun `given routine files in settings then choose exports and open import without Home actions`() = runDesktopComposeUiTest(1000,1200) {
        var exports=emptySet<String>()
        var imports=0
        val transfer=object : com.jagdeep.shared.routines.RoutineTransfer {
            override val isAvailable=true
            override val routines=kotlinx.coroutines.flow.flowOf(listOf(com.jagdeep.shared.routines.TransferRoutine("one","First routine"),com.jagdeep.shared.routines.TransferRoutine("two","Second routine")))
            override suspend fun importRoutines(): String? { imports++; return null }
            override suspend fun exportRoutines(ids:Set<String>):String? { exports=ids; return null }
        }
        val vm=SettingsViewModel(InMemoryPreferencesStore(AppPreferences()),gate,routineTransfer=transfer)
        setContent { AppTheme { SettingsScreen(vm,onBack={},appVersion="1.0.0") } }
        onNodeWithText("Export routines").performScrollTo().performClick()
        onNodeWithText("Select routines to export").assertExists()
        onNodeWithText("Second routine").performClick()
        onNodeWithText("Export selected").performClick()
        waitUntil { exports.isNotEmpty() }
        assertEquals(setOf("one"),exports)
        onNodeWithText("Import routines").performScrollTo().performClick()
        waitUntil { imports==1 }
    }

    @Test
    fun `setup and advanced details open only on request`() = runDesktopComposeUiTest(1000, 1400) {
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate)
        setContent { AppTheme { SettingsScreen(vm, onBack = {}, appVersion = "1.0.0") } }
        onNodeWithText("Chrome extension setup").assertDoesNotExist()
        onNodeWithText("Set up extension").performScrollTo().performClick()
        onNodeWithText("Chrome extension setup").assertExists()
        onNodeWithText("Done").performClick()
        onNodeWithText("Developer mode").assertDoesNotExist()
        onNodeWithText("Developer options").performScrollTo().performClick()
        onNodeWithText("Developer mode").assertExists()
    }

    @Test
    fun `given the safety switch when clicked then the choice is saved`() = runDesktopComposeUiTest(1000, 1200) {
        val store = InMemoryPreferencesStore(AppPreferences())
        val viewModel = SettingsViewModel(store, gate)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        saveShot("settings")
        onNodeWithText("Stop when I move the pointer").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { !store.data.first().stopWhenPointerMoves } }
        assertFalse(runBlocking { store.data.first().stopWhenPointerMoves })
    }

    @Test
    fun `given the display switch when clicked then the choice is saved`() = runDesktopComposeUiTest(1000, 1400) {
        val store = InMemoryPreferencesStore(AppPreferences())
        val viewModel = SettingsViewModel(store, gate)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        onNodeWithText("Keep the display on while running").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { !store.data.first().keepDisplayOn } }
    }

    @Test
    fun `given missing access when shown then the permission row offers the way to allow it`() = runDesktopComposeUiTest(1000, 1200) {
        gate.current = InputAccess.NeedsPermission
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate)
        setContent { AppTheme(darkTheme = true) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        onNodeWithText("Open settings").assertExists()
    }
}

/** Set UI_TEST_SHOT_DIR to save what a test rendered, for reviewing a layout by eye. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}

