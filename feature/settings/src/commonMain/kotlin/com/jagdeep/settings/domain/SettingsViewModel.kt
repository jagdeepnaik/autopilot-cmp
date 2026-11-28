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
    val extension: com.jagdeep.shared.browser.BrowserExtensionStatus = com.jagdeep.shared.browser.BrowserExtensionStatus(),
    val testingSound: Boolean = false,
    val soundProblem: String? = null,
    val routineFilesAvailable: Boolean = false,
    val transferableRoutines: List<com.jagdeep.shared.routines.TransferRoutine> = emptyList(),
    val transferringRoutines: Boolean = false,
    val displayWakeProblem: String? = null,
    val answerBankFolder: String? = null,
    val compass: com.jagdeep.shared.compass.CompassSyncState = com.jagdeep.shared.compass.CompassSyncState(),
    /** True while a Compass sign-in request is on its way. */
    val compassSigningIn: Boolean = false,
    /** Why the last sign-in didn't work, in plain words; null when there's nothing to explain. */
    val compassSignInProblem: String? = null,
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
    private val answerBanks: com.jagdeep.shared.answers.AnswerBankFiles = com.jagdeep.shared.answers.NoAnswerBankFiles,
    extensionStatus: Flow<com.jagdeep.shared.browser.BrowserExtensionStatus> = flowOf(com.jagdeep.shared.browser.BrowserExtensionStatus()),
    private val testSound: suspend () -> String? = { "Sound testing isn't available on this platform." },
    private val compassSync: com.jagdeep.shared.compass.CompassSync = com.jagdeep.shared.compass.NoCompassSync,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(access = accessGate.access(), routineFilesAvailable = routineTransfer.isAvailable, answerBankFolder=answerBanks.folderPath))
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()
    fun openAnswerBankFolder() { viewModelScope.launch {
        try { answerBanks.openFolder()?.let { mutableMessages.send(it) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { mutableMessages.send("Couldn't open the answer-bank folder. Check folder permissions.") }
    } }

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
    /** The password is passed straight to the sign-in request; it is never kept in state. */
    fun signInToCompass(email: String, password: String) {
        if (state.value.compassSigningIn) return
        if (email.isBlank() || password.isEmpty()) {
            mutableState.update { it.copy(compassSignInProblem = "Enter your Compass email and password.") }
            return
        }
        mutableState.update { it.copy(compassSigningIn = true, compassSignInProblem = null) }
        viewModelScope.launch {
            val result = try { compassSync.signIn(email, password) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { com.jagdeep.shared.compass.CompassSignInResult.ServerTrouble }
            mutableState.update { it.copy(compassSigningIn = false, compassSignInProblem = compassSignInProblem(result)) }
            if (result == com.jagdeep.shared.compass.CompassSignInResult.SignedIn) mutableMessages.send("Signed in to Compass. Syncing answers now.")
        }
    }

    fun syncCompassNow() = compassSync.syncNow()

    fun signOutOfCompass() {
        viewModelScope.launch {
            try { compassSync.signOut(); mutableMessages.send("Signed out of Compass. Answers already on this computer stay here.") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessages.send("Couldn't finish signing out. Try again.") }
        }
    }

    init {
        viewModelScope.launch { compassSync.state.collect { sync -> mutableState.update { it.copy(compass = sync) } } }
        viewModelScope.launch { extensionStatus.collect { status -> mutableState.update { it.copy(extension = status) } } }
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
    fun updateKeepDisplayOnWhileAppOpen(enabled: Boolean) = save(state.value.preferences.copy(keepDisplayOnWhileAppOpen = enabled))
    fun updateAssessmentSounds(enabled: Boolean) = save(state.value.preferences.copy(assessmentSoundsEnabled = enabled))

    fun testAssessmentSound() {
        if (!state.value.preferences.assessmentSoundsEnabled || state.value.testingSound) return
        mutableState.update { it.copy(testingSound = true, soundProblem = null) }
        viewModelScope.launch {
            try {
                val problem = testSound()
                mutableState.update { it.copy(soundProblem = problem) }
                mutableMessages.send(problem ?: "Test sound sent to your audio output. If silent, check the Windows volume mixer and selected output device.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(soundProblem = "Couldn't play the alert. Check the selected audio output and volume mixer.") } }
            finally { mutableState.update { it.copy(testingSound = false) } }
        }
    }

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

internal fun compassSignInProblem(result: com.jagdeep.shared.compass.CompassSignInResult): String? = when (result) {
    com.jagdeep.shared.compass.CompassSignInResult.SignedIn -> null
    com.jagdeep.shared.compass.CompassSignInResult.WrongEmailOrPassword -> "That email and password don't match a Compass account. Check them and try again."
    com.jagdeep.shared.compass.CompassSignInResult.Offline -> "Couldn't reach Compass. Check your internet connection and try again."
    com.jagdeep.shared.compass.CompassSignInResult.RateLimited -> "Too many sign-in attempts. Wait a few minutes, then try again."
    com.jagdeep.shared.compass.CompassSignInResult.ServerTrouble -> "Compass isn't responding properly right now. Try again later."
    com.jagdeep.shared.compass.CompassSignInResult.SecureStorageUnavailable -> "AutoPilot couldn't save your session securely, so you weren't signed in. Check that your system keychain is unlocked."
    com.jagdeep.shared.compass.CompassSignInResult.Unavailable -> "Compass sync isn't available on this device."
}
