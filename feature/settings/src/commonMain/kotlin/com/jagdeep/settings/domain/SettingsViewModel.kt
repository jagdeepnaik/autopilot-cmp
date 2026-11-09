package com.jagdeep.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val access: InputAccess = InputAccess.Granted,
    /** True while the Chrome extension is checking in. */
    val browserConnected: Boolean = false,
    val routineFilesAvailable: Boolean = false,
    val transferableRoutines: List<com.jagdeep.shared.routines.TransferRoutine> = emptyList(),
    val transferringRoutines: Boolean = false,
    val displayWakeProblem: String? = null,
)

private enum class HotKeyJob(val description: String, val keyIn: (AppPreferences) -> HotKey) {
    Toggle("starting and stopping", AppPreferences::toggleHotKey),
    Capture("picking points", AppPreferences::captureHotKey),
    PageScroll("stopping scroll and click", AppPreferences::pageScrollHotKey),
}

class SettingsViewModel(
    private val preferencesStore: PreferencesStore<AppPreferences>,
    private val accessGate: InputAccessGate,
    browserConnected: Flow<Boolean> = flowOf(false),
    /** Puts the Chrome extension in a folder and shows it; returns a problem to explain, or null. */
    private val installExtension: suspend () -> String? = { null },
    private val routineTransfer: com.jagdeep.shared.routines.RoutineTransfer = com.jagdeep.shared.routines.NoRoutineTransfer,
    displayWakeProblem: Flow<String?> = flowOf(null),
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(access = accessGate.access(), routineFilesAvailable = routineTransfer.isAvailable))
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    fun importRoutines() = transfer { routineTransfer.importRoutines() }
    fun exportRoutines(ids: Set<String>) = transfer { routineTransfer.exportRoutines(ids) }
    private fun transfer(action: suspend () -> String?) {
        if (!routineTransfer.isAvailable || state.value.transferringRoutines) return
        mutableState.update { it.copy(transferringRoutines = true) }
        viewModelScope.launch {
            try { action()?.let { mutableMessages.send(it) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessages.send("Couldn't transfer routines. Check the file and try again.") }
            finally { mutableState.update { it.copy(transferringRoutines = false) } }
        }
    }
    init {
        viewModelScope.launch { displayWakeProblem.collect { problem -> mutableState.update { it.copy(displayWakeProblem = problem) } } }
        viewModelScope.launch { routineTransfer.routines.collect { items -> mutableState.update { it.copy(transferableRoutines = items) } } }
        viewModelScope.launch {
            preferencesStore.data.collect { preferences -> mutableState.update { it.copy(preferences = preferences) } }
        }
        viewModelScope.launch { browserConnected.collect { connected -> mutableState.update { it.copy(browserConnected = connected) } } }
    }

    fun updateThemeMode(mode: ThemeMode) = save(state.value.preferences.copy(themeMode = mode))

    fun updateColorPalette(palette: ColorPalette) = save(state.value.preferences.copy(colorPalette = palette))

    fun updateStopWhenPointerMoves(enabled: Boolean) = save(state.value.preferences.copy(stopWhenPointerMoves = enabled))

    fun updateStopAtScreenCorner(enabled: Boolean) = save(state.value.preferences.copy(stopAtScreenCorner = enabled))

    fun showExtension() {
        viewModelScope.launch { installExtension()?.let { mutableMessages.send(it) } }
    }

    fun updateDeveloperMode(enabled: Boolean) = save(state.value.preferences.copy(developerMode = enabled))

    fun updateKeepDisplayOn(enabled: Boolean) = save(state.value.preferences.copy(keepDisplayOn = enabled))
    fun updateAssessmentSounds(enabled: Boolean) = save(state.value.preferences.copy(assessmentSoundsEnabled = enabled))

    /** One key can't do two jobs, so a clash keeps the saved choice and says why. */
    fun updateToggleHotKey(key: HotKey) = assignKey(key, HotKeyJob.Toggle) { copy(toggleHotKey = key) }

    fun updateCaptureHotKey(key: HotKey) = assignKey(key, HotKeyJob.Capture) { copy(captureHotKey = key) }


    fun updatePageScrollHotKey(key: HotKey) = assignKey(key, HotKeyJob.PageScroll) { copy(pageScrollHotKey = key) }

    private fun assignKey(key: HotKey, job: HotKeyJob, change: AppPreferences.() -> AppPreferences) {
        val preferences = state.value.preferences
        val clash = HotKeyJob.entries.firstOrNull { it != job && it.keyIn(preferences) == key }
        if (clash != null) return explainClash(key, clash.description)
        save(preferences.change())
    }

    fun requestAccess() {
        if (!accessGate.requestAccess()) {
            viewModelScope.launch { mutableMessages.send("Couldn't open System Settings. Open Privacy & Security → Accessibility yourself.") }
        }
        refreshAccess()
    }

    /** Access is changed outside the app, so the screen asks again when it opens. */
    fun refreshAccess() = mutableState.update { it.copy(access = accessGate.access()) }

    private fun explainClash(key: HotKey, job: String) {
        viewModelScope.launch { mutableMessages.send("${key.name} is already used for $job.") }
    }

    private fun save(preferences: AppPreferences) {
        mutableState.update { it.copy(preferences = preferences) }
        viewModelScope.launch { preferencesStore.save(preferences) }
    }
}
