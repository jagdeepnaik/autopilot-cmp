package com.jagdeep.home.domain.compass

import com.jagdeep.persistence.preferences.SecurePreferencesStore
import com.jagdeep.shared.compass.CompassSignInResult
import com.jagdeep.shared.compass.CompassSyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CompassSyncEngineTest {
    private class FakeCredentials : SecurePreferencesStore<CompassCredentials> {
        val value = MutableStateFlow<CompassCredentials?>(null)
        override val data = value
        override suspend fun save(value: CompassCredentials) { this.value.value = value }
        override suspend fun clear() { value.value = null }
    }

    private class FakeApi : CompassApi {
        var refreshes = 0
        var validAccess = "access-1"
        val logouts = mutableListOf<String>()
        val submitted = mutableListOf<List<CompassSubmission>>()
        val pages = mutableMapOf<String, MutableList<CompassEntriesPage>>()
        val failures = ArrayDeque<CompassApiException>()
        var refreshFails = false
        val refreshFailures = ArrayDeque<CompassApiException>()
        var refreshCalls = 0
        var loginFails: CompassApiException? = null
        var rejectClientId: String? = null
        var statusFor: (CompassSubmission) -> String = { "pending" }

        private fun session(n: Int) = CompassSession("access-$n", 900, "refresh-$n", null, CompassUser("u1", "Test User"))

        override suspend fun login(email: String, password: String, deviceName: String): CompassSession {
            loginFails?.let { throw it }
            return session(1)
        }
        override suspend fun refresh(refreshToken: String): CompassSession {
            refreshCalls++
            refreshFailures.removeFirstOrNull()?.let { throw it }
            if (refreshFails) throw CompassApiException.Unauthorized()
            refreshes++
            validAccess = "access-${refreshes + 1}"
            return session(refreshes + 1)
        }
        override suspend fun logout(refreshToken: String) { logouts += refreshToken }
        override suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CompassEntriesPage {
            failures.removeFirstOrNull()?.let { throw it }
            if (accessToken != validAccess) throw CompassApiException.Unauthorized()
            return pages[bank]?.removeFirstOrNull() ?: CompassEntriesPage(emptyList(), after, false)
        }
        override suspend fun submit(accessToken: String, bank: String, submissions: List<CompassSubmission>): List<CompassSubmissionResult> {
            if (accessToken != validAccess) throw CompassApiException.Unauthorized()
            if (submissions.any { it.clientId == rejectClientId }) throw CompassApiException.Rejected(400)
            submitted += submissions
            return submissions.map { CompassSubmissionResult(it.clientId, statusFor(it)) }
        }
    }

    private class FakeStore : CompassQaStore {
        override val banks = listOf("smy", "olp")
        val revisions = mutableMapOf("smy" to 0L, "olp" to 0L)
        val applied = mutableListOf<CompassEntry>()
        val pendingByBank = mutableMapOf<String, MutableList<CompassSubmission>>()
        val marked = mutableMapOf<String, String>()
        override suspend fun lastRevision(bank: String) = revisions[bank]
        override suspend fun apply(bank: String, entries: List<CompassEntry>, nextAfter: Long): Boolean { applied += entries; revisions[bank] = nextAfter; return true }
        override suspend fun pending(bank: String, limit: Int) = pendingByBank[bank].orEmpty().filter { it.clientId !in marked }.take(limit)
        override suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean { marked += statuses; return true }
        override suspend fun pendingCount() = pendingByBank.values.flatten().count { it.clientId !in marked }
    }

    private val questionKey = "a".repeat(64)
    private val answerKey = "b".repeat(64)

    private fun entry(id: String, revision: Long, bank: String = "smy") = CompassEntry(id, bank, "bank.json", "Module", "Q $id?", questionKey,
        listOf(CompassOption(1, "A"), CompassOption(2, "B")), listOf(CompassAnswer("B", answerKey)), status = "approved", revision = revision)

    private fun submission(id: String, options: Int = 2, answerKey: String = this.answerKey) = CompassSubmission(id, "bank.json", "Module", "Q $id?", questionKey,
        (1..options).map { CompassOption(it, "O$it") }, CompassAnswer("O1", answerKey), "2026-11-28T08:00:00Z", "2026-11-28T08:01:00Z")

    private class Setup(test: TestScope) {
        // Not backgroundScope: advanceUntilIdle must wait for syncs. Cancelled when each test ends.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val api = FakeApi()
        val store = FakeStore()
        val credentials = FakeCredentials()
        val logs = mutableListOf<String>()
        val engine = CompassSyncEngine(api, store, credentials, scope, now = { test.testScheduler.currentTime }, deviceName = "AutoPilot on test", log = { logs += it }, debounceMillis = 1_000)
    }

    private fun syncTest(block: suspend TestScope.(Setup) -> Unit): TestResult = runTest {
        val setup = Setup(this)
        try { block(setup) } finally { setup.scope.cancel() }
    }

    @Test fun given_signed_out_when_started_then_nothing_is_requested() = syncTest { setup ->
        setup.engine.start()
        advanceUntilIdle()
        assertNull(setup.engine.state.value.account)
        assertTrue(setup.store.applied.isEmpty())
        assertNull(setup.engine.state.value.lastOutcome)
    }

    @Test fun given_valid_sign_in_when_syncing_then_pages_are_applied_and_confirmed_answers_are_sent_once() = syncTest { setup ->
        setup.api.pages["smy"] = mutableListOf(CompassEntriesPage(listOf(entry("e1", 5)), 5, true), CompassEntriesPage(listOf(entry("e2", 9)), 9, false))
        setup.store.pendingByBank["smy"] = (1..150).map { submission("c$it") }.toMutableList()
        setup.engine.start()
        assertEquals(CompassSignInResult.SignedIn, setup.engine.signIn(" person@example.com ", "test-password"))
        advanceUntilIdle()
        assertEquals("refresh-1", setup.credentials.value.value?.refreshToken)
        assertEquals(listOf("e1", "e2"), setup.store.applied.map { it.id })
        assertEquals(9L, setup.store.revisions["smy"])
        // Batches of at most 100.
        assertEquals(listOf(100, 50), setup.api.submitted.map { it.size })
        assertEquals(150, setup.store.marked.size)
        val state = setup.engine.state.value
        assertEquals("Test User", state.account?.name)
        assertEquals(CompassSyncOutcome.Synced, state.lastOutcome)
        assertEquals(2, state.lastDownloaded)
        assertEquals(150, state.lastSent)
        assertEquals(0, state.pendingUploads)
        assertFalse(state.syncing)
        // A second sync sends nothing again.
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(150, setup.api.submitted.flatten().size)
    }

    @Test fun given_any_documented_status_when_submitting_then_each_is_marked_sent_and_unknown_ones_are_not() = syncTest { setup ->
        val statuses = listOf("pending", "duplicate", "already_known", "conflict", "surprise")
        setup.store.pendingByBank["smy"] = statuses.indices.map { submission("s$it") }.toMutableList()
        setup.api.statusFor = { statuses[it.clientId.drop(1).toInt()] }
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(mapOf("s0" to "pending", "s1" to "duplicate", "s2" to "already_known", "s3" to "conflict"), setup.store.marked)
        assertEquals(1, setup.engine.state.value.pendingUploads)
    }

    @Test fun given_an_answer_outside_the_contract_limits_when_syncing_then_it_is_never_sent() = syncTest { setup ->
        setup.store.pendingByBank["smy"] = mutableListOf(submission("ok"), submission("too-many", options = 13))
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(listOf("ok"), setup.api.submitted.flatten().map { it.clientId })
        assertEquals("not_sendable", setup.store.marked["too-many"])
    }

    @Test fun given_a_batch_compass_rejects_when_syncing_then_the_other_answers_are_still_sent() = syncTest { setup ->
        setup.store.pendingByBank["smy"] = mutableListOf(submission("a"), submission("bad"), submission("c"))
        setup.api.rejectClientId = "bad"
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(mapOf("a" to "pending", "bad" to "rejected", "c" to "pending"), setup.store.marked)
    }

    @Test fun given_an_expired_access_token_when_syncing_then_it_refreshes_once_rotates_and_retries() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        // Compass revokes the access token early: the next call gets 401.
        setup.api.validAccess = "access-other"
        setup.api.refreshes = 0
        setup.api.pages["smy"] = mutableListOf(CompassEntriesPage(listOf(entry("e3", 12)), 12, false))
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(1, setup.api.refreshes)
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
        assertEquals(listOf("e3"), setup.store.applied.map { it.id })
        assertEquals(CompassSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
    }

    @Test fun given_access_expiry_time_passed_when_syncing_then_it_refreshes_before_calling() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        advanceTimeBy(16 * 60_000L)
        setup.api.validAccess = "access-2"
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(1, setup.api.refreshes)
        assertEquals(CompassSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
    }

    @Test fun given_a_revoked_refresh_token_when_syncing_then_the_session_is_wiped_and_sign_in_is_requested() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.api.validAccess = "access-other"
        setup.api.refreshFails = true
        setup.engine.syncNow()
        advanceUntilIdle()
        assertNull(setup.credentials.value.value)
        val state = setup.engine.state.value
        assertNull(state.account)
        assertTrue(state.signInExpired)
        assertEquals(CompassSyncOutcome.SignInNeeded, state.lastOutcome)
    }

    @Test fun given_a_lost_refresh_reply_when_refreshing_then_it_retries_once_with_the_same_token_only() = syncTest { setup ->
        setup.credentials.value.value = CompassCredentials("refresh-1", "Saved User", "u1")
        setup.api.validAccess = "access-2"
        setup.api.refreshFailures += CompassApiException.Offline()
        setup.engine.start()
        advanceUntilIdle()
        assertEquals(2, setup.api.refreshCalls)
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
        assertEquals(CompassSyncOutcome.Synced, setup.engine.state.value.lastOutcome)

        // Two lost replies in a row: no third attempt (it would count as token reuse); the session is kept for later.
        setup.api.refreshCalls = 0
        setup.api.validAccess = "access-other"
        setup.api.refreshFailures += listOf(CompassApiException.Offline(), CompassApiException.Offline())
        setup.engine.syncNow()
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(2, setup.api.refreshCalls)
        assertEquals(CompassSyncOutcome.Offline, setup.engine.state.value.lastOutcome)
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
    }

    @Test fun given_an_answer_key_outside_the_contract_limits_when_syncing_then_it_waits_and_is_not_given_up() = syncTest { setup ->
        setup.store.pendingByBank["smy"] = mutableListOf(submission("hex"), submission("plain", answerKey = "plain answer text"), submission("huge", answerKey = "x".repeat(1_001)))
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(listOf("hex", "plain"), setup.api.submitted.flatten().map { it.clientId })
        assertEquals(mapOf("hex" to "pending", "plain" to "pending"), setup.store.marked)
        assertEquals(1, setup.engine.state.value.pendingUploads)
    }

    @Test fun given_a_saved_session_when_the_app_starts_then_it_syncs_without_asking_for_a_password() = syncTest { setup ->
        setup.credentials.value.value = CompassCredentials("refresh-1", "Saved User", "u1")
        setup.api.validAccess = "access-2"
        setup.api.pages["olp"] = mutableListOf(CompassEntriesPage(listOf(entry("o1", 3, bank = "olp")), 3, false))
        setup.engine.start()
        advanceUntilIdle()
        assertEquals(1, setup.api.refreshes)
        assertEquals(listOf("o1"), setup.store.applied.map { it.id })
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
    }

    @Test fun given_network_and_server_failures_when_syncing_then_it_backs_off_and_honours_retry_after() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += listOf(CompassApiException.Offline(), CompassApiException.ServerTrouble(503), CompassApiException.RateLimited(120))
        val started = currentTime
        advanceUntilIdle()
        assertEquals(CompassSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
        // 2 s and 4 s back-off, then the 120 s Compass asked for.
        assertTrue(currentTime - started >= 126_000)
    }

    @Test fun given_compass_unreachable_when_syncing_then_it_reports_offline_and_tries_again_later() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += List(3) { CompassApiException.Offline() }
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(CompassSyncOutcome.Offline, setup.engine.state.value.lastOutcome)
        assertNull(setup.engine.state.value.lastSyncAtMillis)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(CompassSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
    }

    @Test fun given_wrong_credentials_when_signing_in_then_nothing_is_stored() = syncTest { setup ->
        setup.api.loginFails = CompassApiException.Unauthorized()
        setup.engine.start()
        assertEquals(CompassSignInResult.WrongEmailOrPassword, setup.engine.signIn("person@example.com", "wrong-password"))
        setup.api.loginFails = CompassApiException.Offline()
        assertEquals(CompassSignInResult.Offline, setup.engine.signIn("person@example.com", "test-password"))
        assertNull(setup.credentials.value.value)
        assertNull(setup.engine.state.value.account)
    }

    @Test fun given_secure_storage_fails_when_signing_in_then_the_session_is_ended_and_reported() = syncTest { setup ->
        val failing = object : SecurePreferencesStore<CompassCredentials> {
            override val data = MutableStateFlow<CompassCredentials?>(null)
            override suspend fun save(value: CompassCredentials) = throw IllegalStateException("no keychain")
            override suspend fun clear() = Unit
        }
        val engine = CompassSyncEngine(setup.api, setup.store, failing, backgroundScope, { currentTime }, "AutoPilot on test")
        assertEquals(CompassSignInResult.SecureStorageUnavailable, engine.signIn("person@example.com", "test-password"))
        assertEquals(listOf("refresh-1"), setup.api.logouts)
        assertNull(engine.state.value.account)
    }

    @Test fun given_signed_in_when_signing_out_then_the_token_is_wiped_and_compass_is_told() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.engine.signOut()
        advanceUntilIdle()
        assertNull(setup.credentials.value.value)
        assertEquals(listOf("refresh-1"), setup.api.logouts)
        val state = setup.engine.state.value
        assertNull(state.account)
        assertFalse(state.signInExpired)
        // Nothing more happens until the user signs in again.
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(1, setup.api.logouts.size)
    }

    @Test fun given_several_saved_answers_when_changes_arrive_then_one_debounced_sync_runs() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.store.pendingByBank["smy"] = mutableListOf(submission("n1"), submission("n2"))
        setup.engine.answersChanged()
        setup.engine.answersChanged()
        runCurrent()
        assertEquals(2, setup.engine.state.value.pendingUploads)
        assertTrue(setup.api.submitted.isEmpty())
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, setup.api.submitted.size)
        assertEquals(0, setup.engine.state.value.pendingUploads)
    }

    @Test fun given_any_sync_when_logging_then_no_token_or_password_appears() = syncTest { setup ->
        setup.store.pendingByBank["smy"] = mutableListOf(submission("x"))
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.api.validAccess = "access-other"
        setup.engine.syncNow()
        advanceUntilIdle()
        setup.engine.signOut()
        advanceUntilIdle()
        assertTrue(setup.logs.isNotEmpty())
        val secrets = listOf("test-password", "refresh-", "access-", "person@example.com")
        setup.logs.forEach { line -> secrets.forEach { secret -> assertFalse(secret in line, "Log line exposes a secret: $line") } }
        assertFalse("refresh-" in CompassCredentials("refresh-9", "User", "u").toString())
        assertFalse("access-" in CompassSession("access-9", 1, "refresh-9", null, CompassUser("u", "User")).toString())
    }
}
