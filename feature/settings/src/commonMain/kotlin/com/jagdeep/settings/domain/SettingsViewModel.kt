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
    val cosmos: com.jagdeep.shared.cosmos.CosmosSyncState = com.jagdeep.shared.cosmos.CosmosSyncState(),
    /** True while a Cosmos sign-in request is on its way. */
    val cosmosSigningIn: Boolean = false,
    /** Why the last sign-in didn't work, in plain words; null when there's nothing to explain. */
    val cosmosSignInProblem: String? = null,
    /** The last sign-in found this computer waiting for the admin's approval; offers Try again. */
    val cosmosMachinePending: Boolean = false,
    /** True while a new Cosmos password is on its way. */
    val cosmosChangingPassword: Boolean = false,
    /** Why the last password change didn't work; null when there's nothing to explain. */
    val cosmosPasswordProblem: String? = null,
    /** True while the one-time browser link is being fetched. */
    val cosmosOpeningBrowser: Boolean = false,
    /** True where routine run logs are kept on this computer (desktop). */
    val runLogsAvailable: Boolean = false,
    /** A newer published build of this app, offered in About; null when there's none or the check failed. */
    val update: com.jagdeep.shared.update.AppUpdate? = null,
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
    private val cosmosSync: com.jagdeep.shared.cosmos.CosmosSync = com.jagdeep.shared.cosmos.NoCosmosSync,
    private val runLogFiles: com.jagdeep.shared.diagnostics.RunLogFiles = com.jagdeep.shared.diagnostics.NoRunLogFiles,
    private val updateCheck: com.jagdeep.shared.update.UpdateCheck = com.jagdeep.shared.update.NoUpdateCheck,
    /** Opens a link in the default browser; false when it couldn't. The link is never logged or kept. */
    private val openBrowser: suspend (String) -> Boolean = { false },
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(access = accessGate.access(), routineFilesAvailable = routineTransfer.isAvailable, answerBankFolder=answerBanks.folderPath,
        runLogsAvailable = runLogFiles.isAvailable))
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()
    fun openAnswerBankFolder() { viewModelScope.launch {
        try { answerBanks.openFolder()?.let { mutableMessages.send(it) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { mutableMessages.send("Couldn't open the answer-bank folder. Check folder permissions.") }
    } }

    fun openRunLogsFolder() { viewModelScope.launch {
        try { runLogFiles.openFolder()?.let { mutableMessages.send(it) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { mutableMessages.send("Couldn't open the logs folder. Check folder permissions.") }
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
    fun signInToCosmos(email: String, password: String) {
        if (state.value.cosmosSigningIn) return
        if (email.isBlank() || password.isEmpty()) {
            mutableState.update { it.copy(cosmosSignInProblem = "Enter your Cosmos email and password.") }
            return
        }
        mutableState.update { it.copy(cosmosSigningIn = true, cosmosSignInProblem = null) }
        viewModelScope.launch {
            val result = try { cosmosSync.signIn(email, password) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { com.jagdeep.shared.cosmos.CosmosSignInResult.ServerTrouble }
            mutableState.update { it.copy(cosmosSigningIn = false, cosmosSignInProblem = cosmosSignInProblem(result),
                cosmosMachinePending = result == com.jagdeep.shared.cosmos.CosmosSignInResult.MachinePending, cosmosPasswordProblem = null) }
            if (result == com.jagdeep.shared.cosmos.CosmosSignInResult.SignedIn) mutableMessages.send(
                if (cosmosSync.state.value.passwordChangeRequired) "Signed in to Cosmos. Choose a new password to start syncing."
                else "Signed in to Cosmos. Syncing answers now.",
            )
        }
    }

    /** Neither password is kept in state; both go straight to the request. */
    fun changeCosmosPassword(currentPassword: String, newPassword: String) {
        if (state.value.cosmosChangingPassword) return
        val tooShort = newPassword.length < com.jagdeep.shared.cosmos.CosmosSync.MIN_PASSWORD_LENGTH
        if (currentPassword.isEmpty() || tooShort) {
            mutableState.update { it.copy(cosmosPasswordProblem = if (tooShort) cosmosPasswordProblem(com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.TooShort)
                else "Enter the temporary password you signed in with.") }
            return
        }
        mutableState.update { it.copy(cosmosChangingPassword = true, cosmosPasswordProblem = null) }
        viewModelScope.launch {
            val result = try { cosmosSync.changePassword(currentPassword, newPassword) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.ServerTrouble }
            mutableState.update { it.copy(cosmosChangingPassword = false, cosmosPasswordProblem = cosmosPasswordProblem(result)) }
            if (result == com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Changed) mutableMessages.send("Password changed. Syncing answers now.")
        }
    }

    /** Signs the default browser in to Cosmos with a one-time link; the link itself is never shown or kept. */
    fun openCosmos() {
        if (state.value.cosmosOpeningBrowser) return
        mutableState.update { it.copy(cosmosOpeningBrowser = true) }
        viewModelScope.launch {
            val problem = try {
                when (val link = cosmosSync.browserLink()) {
                    is com.jagdeep.shared.cosmos.CosmosBrowserLinkResult.Link ->
                        if (openBrowser(link.url)) null else "Couldn't open your web browser. Check that a default browser is set, then try again."
                    is com.jagdeep.shared.cosmos.CosmosBrowserLinkResult.Failed -> cosmosProblemText(link.problem, "open Cosmos")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { cosmosProblemText(com.jagdeep.shared.cosmos.CosmosProblem.ServerTrouble, "open Cosmos") }
            finally { mutableState.update { it.copy(cosmosOpeningBrowser = false) } }
            problem?.let { mutableMessages.send(it) }
        }
    }

    fun syncCosmosNow() = cosmosSync.syncNow()

    fun signOutOfCosmos() {
        viewModelScope.launch {
            try { cosmosSync.signOut(); mutableMessages.send("Signed out of Cosmos. Answers already on this computer stay here.") }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessages.send("Couldn't finish signing out. Try again.") }
        }
    }

    init {
        // Settings (with About) opening is one of the two times the app asks about updates.
        updateCheck.check()
        viewModelScope.launch { updateCheck.update.collect { update -> mutableState.update { it.copy(update = update) } } }
        viewModelScope.launch { cosmosSync.state.collect { sync -> mutableState.update { it.copy(cosmos = sync) } } }
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

internal fun cosmosSignInProblem(result: com.jagdeep.shared.cosmos.CosmosSignInResult): String? = when (result) {
    com.jagdeep.shared.cosmos.CosmosSignInResult.SignedIn -> null
    com.jagdeep.shared.cosmos.CosmosSignInResult.WrongEmailOrPassword -> "That email and password don't match a Cosmos account. Check them and try again."
    com.jagdeep.shared.cosmos.CosmosSignInResult.Offline -> "Couldn't reach Cosmos. Check your internet connection and try again."
    com.jagdeep.shared.cosmos.CosmosSignInResult.RateLimited -> "Too many sign-in attempts. Wait a few minutes, then try again."
    com.jagdeep.shared.cosmos.CosmosSignInResult.ServerTrouble -> "Cosmos isn't responding properly right now. Try again later."
    com.jagdeep.shared.cosmos.CosmosSignInResult.SecureStorageUnavailable -> "AutoPilot couldn't save your session securely, so you weren't signed in. Check that your system keychain is unlocked."
    com.jagdeep.shared.cosmos.CosmosSignInResult.Unavailable -> "Cosmos sync isn't available on this device."
    com.jagdeep.shared.cosmos.CosmosSignInResult.MachinePending -> "This computer is waiting for the admin's approval. Ask the admin to approve it, then choose Try again."
    com.jagdeep.shared.cosmos.CosmosSignInResult.MachineRevoked -> "The admin removed this computer from your account. Ask the admin to approve it again."
    com.jagdeep.shared.cosmos.CosmosSignInResult.AccountDisabled -> "This Cosmos account is disabled. Ask the admin to enable it."
    com.jagdeep.shared.cosmos.CosmosSignInResult.ComputerNotIdentified -> "AutoPilot couldn't identify this computer, so Cosmos can't approve it. Restart AutoPilot and try again."
}

internal fun cosmosPasswordProblem(result: com.jagdeep.shared.cosmos.CosmosPasswordChangeResult): String? = when (result) {
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Changed -> null
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.TooShort -> "Choose a new password with at least ${com.jagdeep.shared.cosmos.CosmosSync.MIN_PASSWORD_LENGTH} characters."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.WrongCurrentPassword -> "That current password isn't right. Enter the temporary password you signed in with."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Offline -> "Couldn't reach Cosmos. Check your internet connection and try again."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.RateLimited -> "Too many attempts. Wait a few minutes, then try again."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.ServerTrouble -> "Cosmos isn't responding properly right now. Try again later."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.SignInNeeded -> "Your Cosmos session ended. Sign in again."
    com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Unavailable -> "Cosmos sync isn't available on this device."
}

/** A failed account request in plain words; [action] completes "Couldn't …" (for example "open Cosmos"). */
fun cosmosProblemText(problem: com.jagdeep.shared.cosmos.CosmosProblem, action: String): String = when (problem) {
    com.jagdeep.shared.cosmos.CosmosProblem.Offline -> "Couldn't $action: Cosmos can't be reached. Check your internet connection and try again."
    com.jagdeep.shared.cosmos.CosmosProblem.RateLimited -> "Couldn't $action: too many requests. Wait a minute, then try again."
    com.jagdeep.shared.cosmos.CosmosProblem.SignInNeeded -> "Couldn't $action: your Cosmos session ended. Sign in again in Settings."
    com.jagdeep.shared.cosmos.CosmosProblem.NotAllowed -> "Couldn't $action: this account isn't allowed to do that."
    com.jagdeep.shared.cosmos.CosmosProblem.NotFound -> "Couldn't $action: Cosmos doesn't have it any more. Refresh the list."
    com.jagdeep.shared.cosmos.CosmosProblem.Conflict -> "Couldn't $action: it conflicts with what Cosmos already has. Refresh the list and check."
    com.jagdeep.shared.cosmos.CosmosProblem.Invalid -> "Couldn't $action: Cosmos didn't accept the details. Check them and try again."
    com.jagdeep.shared.cosmos.CosmosProblem.ServerTrouble -> "Couldn't $action: Cosmos isn't responding properly right now. Try again later."
    com.jagdeep.shared.cosmos.CosmosProblem.Unavailable -> "Couldn't $action: Cosmos isn't available on this device."
}
