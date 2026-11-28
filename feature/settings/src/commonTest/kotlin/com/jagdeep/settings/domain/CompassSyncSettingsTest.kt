package com.jagdeep.settings.domain

import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.ui.util.compassPendingText
import com.jagdeep.settings.ui.util.compassStatusText
import com.jagdeep.settings.ui.util.runLogsText
import com.jagdeep.settings.ui.util.timeAgo
import com.jagdeep.shared.compass.CompassAccount
import com.jagdeep.shared.compass.CompassSignInResult
import com.jagdeep.shared.compass.CompassSync
import com.jagdeep.shared.compass.CompassSyncOutcome
import com.jagdeep.shared.compass.CompassSyncState
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CompassSyncSettingsTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private class FakeSync : CompassSync {
        override val state = MutableStateFlow(CompassSyncState(available = true))
        var result = CompassSignInResult.SignedIn
        var gate: CompletableDeferred<Unit>? = null
        val signIns = mutableListOf<Pair<String, String>>()
        var syncs = 0
        var signOuts = 0
        override suspend fun signIn(email: String, password: String): CompassSignInResult {
            signIns += email to password
            gate?.await()
            if (result == CompassSignInResult.SignedIn) state.value = state.value.copy(account = CompassAccount("Tester"))
            return result
        }
        override suspend fun signOut() { signOuts++; state.value = state.value.copy(account = null) }
        override fun syncNow() { syncs++ }
        override fun answersChanged() = Unit
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(); advanceUntilIdle() } finally { Dispatchers.resetMain() }
    }

    @Test fun given_signed_out_when_signing_in_then_the_account_appears_and_the_password_is_not_kept() = test {
        val sync = FakeSync().apply { gate = CompletableDeferred() }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = sync)
        advanceUntilIdle()
        assertNull(viewModel.state.value.compass.account)
        viewModel.signInToCompass("person@example.com", "test-password")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.compassSigningIn)
        // A second tap while waiting doesn't send another request.
        viewModel.signInToCompass("person@example.com", "test-password")
        sync.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sync.signIns.size)
        val state = viewModel.state.value
        assertFalse(state.compassSigningIn)
        assertNull(state.compassSignInProblem)
        assertEquals("Tester", state.compass.account?.name)
        assertFalse("test-password" in state.toString())
        assertEquals("Signed in to Compass. Syncing answers now.", viewModel.messages.first())
    }

    @Test fun given_wrong_credentials_when_signing_in_then_a_plain_explanation_is_shown() = test {
        val sync = FakeSync().apply { result = CompassSignInResult.WrongEmailOrPassword }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = sync)
        viewModel.signInToCompass("person@example.com", "wrong")
        advanceUntilIdle()
        assertEquals("That email and password don't match a Compass account. Check them and try again.", viewModel.state.value.compassSignInProblem)
        assertNull(viewModel.state.value.compass.account)
    }

    @Test fun given_empty_fields_when_signing_in_then_nothing_is_sent() = test {
        val sync = FakeSync()
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = sync)
        viewModel.signInToCompass(" ", "")
        advanceUntilIdle()
        assertTrue(sync.signIns.isEmpty())
        assertEquals("Enter your Compass email and password.", viewModel.state.value.compassSignInProblem)
    }

    @Test fun given_signed_in_when_syncing_and_signing_out_then_both_reach_the_sync_service() = test {
        val sync = FakeSync().apply { state.value = state.value.copy(account = CompassAccount("Tester")) }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = sync)
        advanceUntilIdle()
        viewModel.syncCompassNow()
        assertEquals(1, sync.syncs)
        viewModel.signOutOfCompass()
        advanceUntilIdle()
        assertEquals(1, sync.signOuts)
        assertNull(viewModel.state.value.compass.account)
    }

    @Test fun given_each_sign_in_result_then_every_failure_has_human_words_without_raw_errors() {
        CompassSignInResult.entries.filter { it != CompassSignInResult.SignedIn }.forEach { result ->
            val text = compassSignInProblem(result)!!
            assertTrue(text.endsWith("."), text)
            assertFalse(text.contains("Exception") || text.contains("401") || text.contains("HTTP"), text)
        }
        assertNull(compassSignInProblem(CompassSignInResult.SignedIn))
    }

    @Test fun given_sync_states_then_the_status_reads_as_plain_words() {
        val now = 10 * 60 * 60_000L
        val synced = CompassSyncState(available = true, account = CompassAccount("T"), lastSyncAtMillis = now - 5 * 60_000, lastOutcome = CompassSyncOutcome.Synced, lastDownloaded = 12, lastSent = 1)
        assertEquals("Last synced 5 minutes ago. Received 12 approved answers, sent 1 answer.", compassStatusText(synced, now))
        assertEquals("Last synced just now. Everything is up to date.", compassStatusText(synced.copy(lastSyncAtMillis = now, lastDownloaded = 0, lastSent = 0), now))
        assertEquals("Couldn't reach Compass. AutoPilot keeps working and tries again later. Last synced 5 minutes ago.", compassStatusText(synced.copy(lastOutcome = CompassSyncOutcome.Offline), now))
        assertEquals("Syncing answers with Compass…", compassStatusText(synced.copy(syncing = true), now))
        assertEquals("Not synced yet.", compassStatusText(CompassSyncState(available = true), now))
        CompassSyncOutcome.entries.forEach { outcome -> assertTrue(compassStatusText(synced.copy(lastOutcome = outcome), now).isNotBlank()) }
        assertEquals("1 confirmed answer is waiting to be sent.", compassPendingText(1))
        assertEquals("Every answer you confirmed has been sent.", compassPendingText(0))
        assertEquals("1 hour ago", timeAgo(61 * 60_000L))
        assertEquals("2 days ago", timeAgo(49 * 60 * 60_000L))
    }

    @Test fun given_run_logs_waiting_when_shown_then_the_line_counts_them_and_signed_out_explains_they_stay_here() {
        assertEquals("Run logs: 3 waiting to upload.", runLogsText(3, signedIn = true, syncAvailable = true))
        assertEquals("Run logs: 1 waiting to upload.", runLogsText(1, signedIn = true, syncAvailable = true))
        assertTrue(runLogsText(0, signedIn = true, syncAvailable = true).startsWith("Run logs: all sent."))
        assertTrue(runLogsText(5, signedIn = false, syncAvailable = true).contains("only while you're signed in"))
    }

    @Test fun given_run_logs_on_this_computer_when_open_folder_is_chosen_then_the_folder_opens_and_problems_are_explained() = test {
        var opened = 0
        val files = object : com.jagdeep.shared.diagnostics.RunLogFiles {
            override val isAvailable = true
            override suspend fun openFolder(): String? { opened++; return if (opened > 1) "Couldn't open the logs folder." else null }
        }
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, runLogFiles = files)
        assertTrue(vm.state.value.runLogsAvailable)
        vm.openRunLogsFolder()
        advanceUntilIdle()
        assertEquals(1, opened)
        vm.openRunLogsFolder()
        assertEquals("Couldn't open the logs folder.", vm.messages.first())
        assertFalse(SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate).state.value.runLogsAvailable)
    }
}
