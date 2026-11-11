package com.jagdeep.settings.domain

import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.DEFAULT_COLOR_PALETTE
import com.jagdeep.shared.preferences.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class FakeGate(var current: InputAccess = InputAccess.Granted, var opens: Boolean = true) : InputAccessGate {
        var requests = 0
        override fun access() = current
        override fun requestAccess(): Boolean {
            requests++
            return opens
        }
    }

    private val store = InMemoryPreferencesStore(AppPreferences())

    @Test
    fun `extension version status updates and sound test respects the enabled setting`() = test {
        val extension = kotlinx.coroutines.flow.MutableStateFlow(com.jagdeep.shared.browser.BrowserExtensionStatus("1.14.1", "1.14.0"))
        var sounds = 0
        val viewModel = SettingsViewModel(store, FakeGate(), extensionStatus = extension, testSound = { sounds++; null })
        advanceUntilIdle()
        assertEquals("1.14.1", viewModel.state.value.extension.latestVersion)
        assertTrue(viewModel.state.value.extension.warning!!.contains("out of date"))
        extension.value = extension.value.copy(connectedVersion = "1.14.1")
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.extension.warning)
        viewModel.updateAssessmentSounds(false)
        viewModel.testAssessmentSound()
        advanceUntilIdle()
        assertEquals(0, sounds)
        viewModel.updateAssessmentSounds(true)
        viewModel.testAssessmentSound()
        advanceUntilIdle()
        assertEquals(1, sounds)
        assertFalse(viewModel.state.value.testingSound)
    }

    @Test
    fun `sound test displays an audio output failure`() = test {
        val viewModel = SettingsViewModel(store, FakeGate(), testSound = { "No audio output." })
        advanceUntilIdle()
        viewModel.testAssessmentSound()
        advanceUntilIdle()
        assertEquals("No audio output.", viewModel.state.value.soundProblem)
    }

    @Test
    fun `given routine file actions then settings forwards selections and prevents duplicate imports`() = test {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var imports = 0
        var exported = emptySet<String>()
        val transfer = object : com.jagdeep.shared.routines.RoutineTransfer {
            override val isAvailable = true
            override val routines = kotlinx.coroutines.flow.flowOf(listOf(com.jagdeep.shared.routines.TransferRoutine("a","First")))
            override suspend fun importRoutines(): String? { imports++; gate.await(); return "Imported 1 routine." }
            override suspend fun exportRoutines(ids: Set<String>): String? { exported=ids; return "Exported 1 routine." }
        }
        val vm=SettingsViewModel(store,FakeGate(),routineTransfer=transfer)
        advanceUntilIdle()
        assertTrue(vm.state.value.routineFilesAvailable)
        assertEquals("First",vm.state.value.transferableRoutines.single().name)
        vm.exportRoutines(setOf("a")); advanceUntilIdle()
        assertEquals(setOf("a"),exported); assertEquals("Exported 1 routine.",vm.messages.first())
        vm.importRoutines(); vm.importRoutines(); runCurrent()
        assertEquals(1,imports); assertTrue(vm.state.value.transferringRoutines)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.transferringRoutines); assertEquals("Imported 1 routine.",vm.messages.first())
    }

    @Test
    fun `given the display setting when changed then it's on by default and saved`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.preferences.keepDisplayOn)

        viewModel.updateKeepDisplayOn(false)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.preferences.keepDisplayOn)
        assertFalse(store.data.first().keepDisplayOn)
    }

    @Test
    fun `given the Chrome extension when connected or set up then Settings shows it and explains problems`() = test {
        val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val viewModel = SettingsViewModel(store, FakeGate(), connected, installExtension = { "No disk space." })
        advanceUntilIdle()
        assertFalse(viewModel.state.value.browserConnected)
        connected.value = true
        advanceUntilIdle()
        assertTrue(viewModel.state.value.browserConnected)

        viewModel.showExtension()
        advanceUntilIdle()
        assertEquals("No disk space.", viewModel.messages.first())
    }

    @Test
    fun `given developer mode when switched on then it's saved, and it starts off`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        assertFalse(viewModel.state.value.preferences.developerMode)
        viewModel.updateDeveloperMode(true)
        advanceUntilIdle()
        assertTrue(store.data.first().developerMode)
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given no choices yet when shown then the safe defaults apply`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        val preferences = viewModel.state.value.preferences
        assertEquals(HotKey.F6, preferences.toggleHotKey)
        assertEquals(HotKey.F7, preferences.captureHotKey)
        assertTrue(preferences.stopWhenPointerMoves)
        assertTrue(preferences.stopAtScreenCorner)
        assertEquals(DEFAULT_COLOR_PALETTE, preferences.colorPalette)
        assertEquals(ThemeMode.System, preferences.themeMode)
    }

    @Test
    fun `given appearance choices when selected then they persist`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        viewModel.updateThemeMode(ThemeMode.Dark)
        viewModel.updateColorPalette(ColorPalette.Ocean)
        advanceUntilIdle()
        assertEquals(ThemeMode.Dark, store.data.first().themeMode)
        assertEquals(ColorPalette.Ocean, store.data.first().colorPalette)
    }

    @Test
    fun `given safety switches when turned off then they persist`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        viewModel.updateStopWhenPointerMoves(false)
        viewModel.updateStopAtScreenCorner(false)
        advanceUntilIdle()
        assertFalse(store.data.first().stopWhenPointerMoves)
        assertFalse(store.data.first().stopAtScreenCorner)
    }

    @Test
    fun `given a free key when chosen then it persists`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        viewModel.updateToggleHotKey(HotKey.F10)
        viewModel.updateCaptureHotKey(HotKey.F11)
        advanceUntilIdle()
        assertEquals(HotKey.F10, store.data.first().toggleHotKey)
        assertEquals(HotKey.F11, store.data.first().captureHotKey)
    }

    @Test
    fun `given a key already in use when chosen then the old choice stays and it says why`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        viewModel.updateToggleHotKey(HotKey.F7)
        advanceUntilIdle()
        assertEquals(HotKey.F6, store.data.first().toggleHotKey)
        assertEquals("F7 is already used for picking points.", viewModel.messages.first())

        viewModel.updateCaptureHotKey(HotKey.F6)
        advanceUntilIdle()
        assertEquals(HotKey.F7, store.data.first().captureHotKey)
        assertEquals("F6 is already used for starting and stopping.", viewModel.messages.first())
    }

    @Test
    fun `given a scroll stop key when it clashes it is refused and a retired key can be reused`() = test {
        val viewModel = SettingsViewModel(store, FakeGate())
        advanceUntilIdle()
        viewModel.updatePageScrollHotKey(HotKey.F7)
        advanceUntilIdle()
        assertEquals(HotKey.F9, store.data.first().pageScrollHotKey)
        assertEquals("F7 is already used for picking points.", viewModel.messages.first())

        viewModel.updatePageScrollHotKey(HotKey.F8)
        advanceUntilIdle()
        assertEquals(HotKey.F8, store.data.first().pageScrollHotKey)
    }

    @Test
    fun `given access changes outside the app when refreshed then the state follows`() = test {
        val gate = FakeGate(InputAccess.NeedsPermission)
        val viewModel = SettingsViewModel(store, gate)
        assertEquals(InputAccess.NeedsPermission, viewModel.state.value.access)
        gate.current = InputAccess.Granted
        viewModel.refreshAccess()
        assertEquals(InputAccess.Granted, viewModel.state.value.access)
    }

    @Test
    fun `given the system screen can't open when requesting access then it explains what to do`() = test {
        val gate = FakeGate(InputAccess.NeedsPermission, opens = false)
        val viewModel = SettingsViewModel(store, gate)
        viewModel.requestAccess()
        advanceUntilIdle()
        assertEquals(1, gate.requests)
        assertTrue(viewModel.messages.first().contains("Accessibility"))
    }
}
