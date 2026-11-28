package com.jagdeep.settings.domain

import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.ui.util.cosmosPendingText
import com.jagdeep.settings.ui.util.cosmosStatusText
import com.jagdeep.settings.ui.util.runLogsText
import com.jagdeep.settings.ui.util.timeAgo
import com.jagdeep.shared.cosmos.CosmosAccount
import com.jagdeep.shared.cosmos.CosmosSignInResult
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncOutcome
import com.jagdeep.shared.cosmos.CosmosSyncState
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
class CosmosSyncSettingsTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private class FakeSync : CosmosSync {
        override val state = MutableStateFlow(CosmosSyncState(available = true))
        var result = CosmosSignInResult.SignedIn
        var gate: CompletableDeferred<Unit>? = null
        val signIns = mutableListOf<Pair<String, String>>()
        var syncs = 0
        var signOuts = 0
        /** Answers for the next sign-ins, in order; [result] once they run out. */
        val results = ArrayDeque<CosmosSignInResult>()
        val codes = mutableListOf<String?>()
        override suspend fun signIn(email: String, password: String, totpCode: String?): CosmosSignInResult {
            signIns += email to password
            codes += totpCode
            gate?.await()
            val answer = results.removeFirstOrNull() ?: result
            if (answer == CosmosSignInResult.SignedIn) state.value = state.value.copy(account = CosmosAccount("Tester"))
            return answer
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
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        advanceUntilIdle()
        assertNull(viewModel.state.value.cosmos.account)
        viewModel.signInToCosmos("person@example.com", "test-password")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.cosmosSigningIn)
        // A second tap while waiting doesn't send another request.
        viewModel.signInToCosmos("person@example.com", "test-password")
        sync.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sync.signIns.size)
        val state = viewModel.state.value
        assertFalse(state.cosmosSigningIn)
        assertNull(state.cosmosSignInProblem)
        assertEquals("Tester", state.cosmos.account?.name)
        assertFalse("test-password" in state.toString())
        assertEquals("Signed in to Cosmos. Syncing answers now.", viewModel.messages.first())
    }

    @Test fun given_wrong_credentials_when_signing_in_then_a_plain_explanation_is_shown() = test {
        val sync = FakeSync().apply { result = CosmosSignInResult.WrongEmailOrPassword }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        viewModel.signInToCosmos("person@example.com", "wrong")
        advanceUntilIdle()
        assertEquals("That email and password don't match a Cosmos account. Check them and try again.", viewModel.state.value.cosmosSignInProblem)
        assertNull(viewModel.state.value.cosmos.account)
    }

    @Test fun given_the_admin_when_cosmos_asks_for_a_code_then_the_same_sign_in_is_sent_again_with_it() = test {
        val sync = FakeSync().apply { results += listOf(CosmosSignInResult.TotpRequired, CosmosSignInResult.SignedIn) }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        // A code typed before Cosmos asked for one isn't sent.
        viewModel.signInToCosmos("admin@example.com", "test-password", "999999")
        advanceUntilIdle()
        var state = viewModel.state.value
        assertTrue(state.cosmosTotpRequired)
        // The contract's sentence is the code field's prompt, not an error.
        assertNull(state.cosmosSignInProblem)
        assertEquals("Enter the code from your authenticator app.", cosmosTotpPrompt)
        assertNull(state.cosmos.account)

        viewModel.signInToCosmos("admin@example.com", "test-password", "123456")
        advanceUntilIdle()
        state = viewModel.state.value
        assertEquals(listOf(null, "123456"), sync.codes)
        assertEquals(listOf("admin@example.com" to "test-password", "admin@example.com" to "test-password"), sync.signIns)
        assertFalse(state.cosmosTotpRequired)
        assertNull(state.cosmosSignInProblem)
        assertEquals("Tester", state.cosmos.account?.name)
        // Neither the password nor the code is kept in state.
        assertFalse("test-password" in state.toString() || "123456" in state.toString())
        assertEquals("Signed in to Cosmos. Syncing answers now.", viewModel.messages.first())
    }

    @Test fun given_a_wrong_code_when_signing_in_then_the_sentence_shows_and_the_code_field_stays() = test {
        val sync = FakeSync().apply { results += listOf(CosmosSignInResult.TotpRequired, CosmosSignInResult.TotpInvalid, CosmosSignInResult.RateLimited) }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        viewModel.signInToCosmos("admin@example.com", "test-password")
        advanceUntilIdle()
        viewModel.signInToCosmos("admin@example.com", "test-password", "000000")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.cosmosTotpRequired)
        assertEquals("That code didn't work. Try the newest one.", viewModel.state.value.cosmosSignInProblem)
        // Too many wrong codes: the existing rate-limit words, and the field stays for later.
        viewModel.signInToCosmos("admin@example.com", "test-password", "111111")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.cosmosTotpRequired)
        assertEquals("Too many sign-in attempts. Wait a few minutes, then try again.", viewModel.state.value.cosmosSignInProblem)
        // An empty code isn't sent.
        viewModel.signInToCosmos("admin@example.com", "test-password", " ")
        advanceUntilIdle()
        assertEquals(3, sync.signIns.size)
        assertEquals("Enter the code from your authenticator app, or a recovery code.", viewModel.state.value.cosmosSignInProblem)
        // Cancel starts again from the email and password.
        viewModel.cancelCosmosTotp()
        assertFalse(viewModel.state.value.cosmosTotpRequired)
        assertNull(viewModel.state.value.cosmosSignInProblem)
    }

    @Test fun given_two_step_sign_in_not_set_up_when_the_admin_signs_in_then_the_server_step_is_explained() = test {
        val sync = FakeSync().apply { result = CosmosSignInResult.TotpSetupRequired }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        viewModel.signInToCosmos("admin@example.com", "test-password")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.cosmosTotpRequired)
        assertEquals("Set up two-step sign-in on the server first (npm run admin:totp).", viewModel.state.value.cosmosSignInProblem)
        assertNull(viewModel.state.value.cosmos.account)
    }

    @Test fun given_codes_as_typed_then_only_plain_codes_up_to_twenty_characters_are_sent() {
        assertEquals("123456", CosmosSync.normalizedTotpCode(" 123 456 "))
        assertEquals("ABCD-EFGH-1234", CosmosSync.normalizedTotpCode("ABCD-EFGH-1234"))
        assertNull(CosmosSync.normalizedTotpCode(null))
        assertNull(CosmosSync.normalizedTotpCode("   "))
        assertNull(CosmosSync.normalizedTotpCode("12#456"))
        assertNull(CosmosSync.normalizedTotpCode("x".repeat(21)))
    }

    @Test fun given_empty_fields_when_signing_in_then_nothing_is_sent() = test {
        val sync = FakeSync()
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        viewModel.signInToCosmos(" ", "")
        advanceUntilIdle()
        assertTrue(sync.signIns.isEmpty())
        assertEquals("Enter your Cosmos email and password.", viewModel.state.value.cosmosSignInProblem)
    }

    @Test fun given_signed_in_when_syncing_and_signing_out_then_both_reach_the_sync_service() = test {
        val sync = FakeSync().apply { state.value = state.value.copy(account = CosmosAccount("Tester")) }
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        advanceUntilIdle()
        viewModel.syncCosmosNow()
        assertEquals(1, sync.syncs)
        viewModel.signOutOfCosmos()
        advanceUntilIdle()
        assertEquals(1, sync.signOuts)
        assertNull(viewModel.state.value.cosmos.account)
    }

    @Test fun given_each_sign_in_result_then_every_failure_has_human_words_without_raw_errors() {
        CosmosSignInResult.entries.filter { it != CosmosSignInResult.SignedIn }.forEach { result ->
            val text = cosmosSignInProblem(result)!!
            assertTrue(text.endsWith("."), text)
            assertFalse(text.contains("Exception") || text.contains("401") || text.contains("HTTP"), text)
        }
        assertNull(cosmosSignInProblem(CosmosSignInResult.SignedIn))
    }

    @Test fun given_sync_states_then_the_status_reads_as_plain_words() {
        val now = 10 * 60 * 60_000L
        val synced = CosmosSyncState(available = true, account = CosmosAccount("T"), lastSyncAtMillis = now - 5 * 60_000, lastOutcome = CosmosSyncOutcome.Synced, lastDownloaded = 12, lastSent = 1)
        assertEquals("Last synced 5 minutes ago. Received 12 approved answers, sent 1 answer.", cosmosStatusText(synced, now))
        assertEquals("Last synced just now. Everything is up to date.", cosmosStatusText(synced.copy(lastSyncAtMillis = now, lastDownloaded = 0, lastSent = 0), now))
        assertEquals("Couldn't reach Cosmos. AutoPilot keeps working and tries again later. Last synced 5 minutes ago.", cosmosStatusText(synced.copy(lastOutcome = CosmosSyncOutcome.Offline), now))
        assertEquals("Syncing answers with Cosmos…", cosmosStatusText(synced.copy(syncing = true), now))
        assertEquals("Not synced yet.", cosmosStatusText(CosmosSyncState(available = true), now))
        CosmosSyncOutcome.entries.forEach { outcome -> assertTrue(cosmosStatusText(synced.copy(lastOutcome = outcome), now).isNotBlank()) }
        assertEquals("1 confirmed answer is waiting to be sent.", cosmosPendingText(1))
        assertEquals("Every answer you confirmed has been sent.", cosmosPendingText(0))
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
