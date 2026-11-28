package com.jagdeep.home.domain.cosmos

import com.jagdeep.persistence.preferences.SecurePreferencesStore
import com.jagdeep.shared.cosmos.CosmosSignInResult
import com.jagdeep.shared.cosmos.CosmosSyncOutcome
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
class CosmosSyncEngineTest {
    private class FakeCredentials : SecurePreferencesStore<CosmosCredentials> {
        val value = MutableStateFlow<CosmosCredentials?>(null)
        override val data = value
        override suspend fun save(value: CosmosCredentials) { this.value.value = value }
        override suspend fun clear() { value.value = null }
    }

    private class FakeApi : CosmosApi {
        var refreshes = 0
        var validAccess = "access-1"
        val logouts = mutableListOf<String>()
        val submitted = mutableListOf<List<CosmosSubmission>>()
        val pages = mutableMapOf<String, MutableList<CosmosEntriesPage>>()
        val failures = ArrayDeque<CosmosApiException>()
        var refreshFails = false
        val refreshFailures = ArrayDeque<CosmosApiException>()
        var refreshCalls = 0
        var loginFails: CosmosApiException? = null
        var rejectClientId: String? = null
        var statusFor: (CosmosSubmission) -> String = { "pending" }

        var role: String? = null
        var mustChange = false
        private fun session(n: Int) = CosmosSession("access-$n", 900, "refresh-$n", null, CosmosUser("u1", "Test User", role), mustChangePassword = mustChange)

        val passwordChanges = mutableListOf<Pair<String, String>>()
        val changeFailures = ArrayDeque<CosmosApiException>()
        override suspend fun changePassword(accessToken: String, currentPassword: String, newPassword: String): CosmosSession? {
            changeFailures.removeFirstOrNull()?.let { throw it }
            if (accessToken != validAccess) throw CosmosApiException.Unauthorized()
            passwordChanges += currentPassword to newPassword
            mustChange = false
            return null
        }
        var browserLinks = 0
        val linkFailures = ArrayDeque<CosmosApiException>()
        override suspend fun browserLink(accessToken: String): CosmosBrowserLink {
            linkFailures.removeFirstOrNull()?.let { throw it }
            if (accessToken != validAccess) throw CosmosApiException.Unauthorized()
            browserLinks++
            return CosmosBrowserLink("https://cosmos.test/link#secret-link-token", 60)
        }
        val created = mutableListOf<CosmosCreateAccountRequest>()
        var createFailure: CosmosApiException? = null
        override suspend fun adminCreateAccount(accessToken: String, request: CosmosCreateAccountRequest): CosmosCreatedAccountResponse {
            createFailure?.let { throw it }
            created += request
            return CosmosCreatedAccountResponse(CosmosAccountWire("a_1", request.name, request.email, "seafarer", "active", request.machineLimit, mustChangePassword = true), "temp-secret-123")
        }
        var approvals = 0
        var approveFailure: CosmosApiException? = null
        override suspend fun adminApproveMachine(accessToken: String, id: String): CosmosMachineWire {
            approvals++
            approveFailure?.let { throw it }
            return CosmosMachineWire(id, "AutoPilot on Windows", "windows", "approved", account = CosmosMachineAccount("a_1", "Asha", "asha@example.com"))
        }

        val logins = mutableListOf<Pair<String, String>>()
        override suspend fun login(email: String, password: String, deviceName: String, machineId: String, platform: String): CosmosSession {
            logins += machineId to platform
            loginFails?.let { throw it }
            return session(1)
        }
        override suspend fun refresh(refreshToken: String): CosmosSession {
            refreshCalls++
            refreshFailures.removeFirstOrNull()?.let { throw it }
            if (refreshFails) throw CosmosApiException.Unauthorized()
            refreshes++
            validAccess = "access-${refreshes + 1}"
            return session(refreshes + 1)
        }
        override suspend fun logout(refreshToken: String) { logouts += refreshToken }
        override suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CosmosEntriesPage {
            failures.removeFirstOrNull()?.let { throw it }
            if (accessToken != validAccess) throw CosmosApiException.Unauthorized()
            return pages[bank]?.removeFirstOrNull() ?: CosmosEntriesPage(emptyList(), after, false)
        }
        override suspend fun submit(accessToken: String, bank: String, submissions: List<CosmosSubmission>): List<CosmosSubmissionResult> {
            if (accessToken != validAccess) throw CosmosApiException.Unauthorized()
            if (submissions.any { it.clientId == rejectClientId }) throw CosmosApiException.Rejected(400)
            submitted += submissions
            return submissions.map { CosmosSubmissionResult(it.clientId, statusFor(it)) }
        }
        val uploads = mutableListOf<String>()
        val uploadFailures = ArrayDeque<CosmosApiException>()
        var uploadStatus: (String) -> String = { "stored" }
        override suspend fun uploadRun(accessToken: String, body: String): String {
            uploadFailures.removeFirstOrNull()?.let { throw it }
            if (accessToken != validAccess) throw CosmosApiException.Unauthorized()
            uploads += body
            return uploadStatus(body)
        }
    }

    private class FakeOutbox : RunLogOutbox {
        val runs = linkedMapOf<String, List<String>>()
        val marks = mutableMapOf<String, RunLogMark>()
        override suspend fun unsent(limit: Int) = runs.keys.filter { it !in marks }.take(limit)
        override suspend fun lines(runId: String) = runs[runId]
        override suspend fun mark(runId: String, mark: RunLogMark) { marks[runId] = mark }
        override suspend fun waitingCount() = runs.keys.count { it !in marks }
        fun add(id: String) { runs[id] = listOf(
            """{"type":"run.start","runId":"$id","at":"2026-11-28T10:00:00.000Z","routineKind":"Click","routineName":"R","appVersion":"v","os":"o","settings":{}}""",
            """{"type":"event","at":"2026-11-28T10:00:01.000Z","seq":1,"level":"info","source":"routine","event":"routine.step","data":{}}""",
            """{"type":"run.end","at":"2026-11-28T10:00:02.000Z","outcome":"Completed","events":1,"durationMs":2000}""",
        ) }
    }

    private class FakeStore : CosmosQaStore {
        override val banks = listOf("smy", "olp")
        val revisions = mutableMapOf("smy" to 0L, "olp" to 0L)
        val applied = mutableListOf<CosmosEntry>()
        val pendingByBank = mutableMapOf<String, MutableList<CosmosSubmission>>()
        val marked = mutableMapOf<String, String>()
        override suspend fun lastRevision(bank: String) = revisions[bank]
        override suspend fun apply(bank: String, entries: List<CosmosEntry>, nextAfter: Long): Boolean { applied += entries; revisions[bank] = nextAfter; return true }
        override suspend fun pending(bank: String, limit: Int) = pendingByBank[bank].orEmpty().filter { it.clientId !in marked }.take(limit)
        override suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean { marked += statuses; return true }
        override suspend fun pendingCount() = pendingByBank.values.flatten().count { it.clientId !in marked }
    }

    private object TestMachine : MachineIdentifierSource {
        override val platform = "macos"
        override fun rawIdentifier() = "TEST-PLATFORM-UUID"
    }

    private val questionKey = "a".repeat(64)
    private val answerKey = "b".repeat(64)

    private fun entry(id: String, revision: Long, bank: String = "smy") = CosmosEntry(id, bank, "bank.json", "Module", "Q $id?", questionKey,
        listOf(CosmosOption(1, "A"), CosmosOption(2, "B")), listOf(CosmosAnswer("B", answerKey)), status = "approved", revision = revision)

    private fun submission(id: String, options: Int = 2, answerKey: String = this.answerKey) = CosmosSubmission(id, "bank.json", "Module", "Q $id?", questionKey,
        (1..options).map { CosmosOption(it, "O$it") }, CosmosAnswer("O1", answerKey), "2026-11-28T08:00:00Z", "2026-11-28T08:01:00Z")

    private class Setup(test: TestScope) {
        // Not backgroundScope: advanceUntilIdle must wait for syncs. Cancelled when each test ends.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val api = FakeApi()
        val store = FakeStore()
        val credentials = FakeCredentials()
        val logs = mutableListOf<String>()
        val outbox = FakeOutbox()
        val engine = CosmosSyncEngine(api, store, credentials, scope, now = { test.testScheduler.currentTime }, deviceName = "AutoPilot on test", log = { logs += it }, debounceMillis = 1_000, runLogs = outbox, machine = TestMachine)
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
        setup.api.pages["smy"] = mutableListOf(CosmosEntriesPage(listOf(entry("e1", 5)), 5, true), CosmosEntriesPage(listOf(entry("e2", 9)), 9, false))
        setup.store.pendingByBank["smy"] = (1..150).map { submission("c$it") }.toMutableList()
        setup.engine.start()
        assertEquals(CosmosSignInResult.SignedIn, setup.engine.signIn(" person@example.com ", "test-password"))
        advanceUntilIdle()
        assertEquals("refresh-1", setup.credentials.value.value?.refreshToken)
        assertEquals(listOf("e1", "e2"), setup.store.applied.map { it.id })
        assertEquals(9L, setup.store.revisions["smy"])
        // Batches of at most 100.
        assertEquals(listOf(100, 50), setup.api.submitted.map { it.size })
        assertEquals(150, setup.store.marked.size)
        val state = setup.engine.state.value
        assertEquals("Test User", state.account?.name)
        assertEquals(CosmosSyncOutcome.Synced, state.lastOutcome)
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

    @Test fun given_a_batch_cosmos_rejects_when_syncing_then_the_other_answers_are_still_sent() = syncTest { setup ->
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
        // Cosmos revokes the access token early: the next call gets 401.
        setup.api.validAccess = "access-other"
        setup.api.refreshes = 0
        setup.api.pages["smy"] = mutableListOf(CosmosEntriesPage(listOf(entry("e3", 12)), 12, false))
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(1, setup.api.refreshes)
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
        assertEquals(listOf("e3"), setup.store.applied.map { it.id })
        assertEquals(CosmosSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
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
        assertEquals(CosmosSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
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
        assertEquals(CosmosSyncOutcome.SignInNeeded, state.lastOutcome)
    }

    @Test fun given_a_lost_refresh_reply_when_refreshing_then_it_retries_once_with_the_same_token_only() = syncTest { setup ->
        setup.credentials.value.value = CosmosCredentials("refresh-1", "Saved User", "u1")
        setup.api.validAccess = "access-2"
        setup.api.refreshFailures += CosmosApiException.Offline()
        setup.engine.start()
        advanceUntilIdle()
        assertEquals(2, setup.api.refreshCalls)
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
        assertEquals(CosmosSyncOutcome.Synced, setup.engine.state.value.lastOutcome)

        // Two lost replies in a row: no third attempt (it would count as token reuse); the session is kept for later.
        setup.api.refreshCalls = 0
        setup.api.validAccess = "access-other"
        setup.api.refreshFailures += listOf(CosmosApiException.Offline(), CosmosApiException.Offline())
        setup.engine.syncNow()
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(2, setup.api.refreshCalls)
        assertEquals(CosmosSyncOutcome.Offline, setup.engine.state.value.lastOutcome)
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
        setup.credentials.value.value = CosmosCredentials("refresh-1", "Saved User", "u1")
        setup.api.validAccess = "access-2"
        setup.api.pages["olp"] = mutableListOf(CosmosEntriesPage(listOf(entry("o1", 3, bank = "olp")), 3, false))
        setup.engine.start()
        advanceUntilIdle()
        assertEquals(1, setup.api.refreshes)
        assertEquals(listOf("o1"), setup.store.applied.map { it.id })
        assertEquals("refresh-2", setup.credentials.value.value?.refreshToken)
    }

    @Test fun given_network_and_server_failures_when_syncing_then_it_backs_off_and_honours_retry_after() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += listOf(CosmosApiException.Offline(), CosmosApiException.ServerTrouble(503), CosmosApiException.RateLimited(120))
        val started = currentTime
        advanceUntilIdle()
        assertEquals(CosmosSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
        // 2 s and 4 s back-off, then the 120 s Cosmos asked for.
        assertTrue(currentTime - started >= 126_000)
    }

    @Test fun given_cosmos_unreachable_when_syncing_then_it_reports_offline_and_tries_again_later() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += List(3) { CosmosApiException.Offline() }
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(CosmosSyncOutcome.Offline, setup.engine.state.value.lastOutcome)
        assertNull(setup.engine.state.value.lastSyncAtMillis)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(CosmosSyncOutcome.Synced, setup.engine.state.value.lastOutcome)
    }

    @Test fun given_wrong_credentials_when_signing_in_then_nothing_is_stored() = syncTest { setup ->
        setup.api.loginFails = CosmosApiException.Unauthorized()
        setup.engine.start()
        assertEquals(CosmosSignInResult.WrongEmailOrPassword, setup.engine.signIn("person@example.com", "wrong-password"))
        setup.api.loginFails = CosmosApiException.Offline()
        assertEquals(CosmosSignInResult.Offline, setup.engine.signIn("person@example.com", "test-password"))
        assertNull(setup.credentials.value.value)
        assertNull(setup.engine.state.value.account)
    }

    @Test fun given_secure_storage_fails_when_signing_in_then_the_session_is_ended_and_reported() = syncTest { setup ->
        val failing = object : SecurePreferencesStore<CosmosCredentials> {
            override val data = MutableStateFlow<CosmosCredentials?>(null)
            override suspend fun save(value: CosmosCredentials) = throw IllegalStateException("no keychain")
            override suspend fun clear() = Unit
        }
        val engine = CosmosSyncEngine(setup.api, setup.store, failing, backgroundScope, { currentTime }, "AutoPilot on test", machine = TestMachine)
        assertEquals(CosmosSignInResult.SecureStorageUnavailable, engine.signIn("person@example.com", "test-password"))
        assertEquals(listOf("refresh-1"), setup.api.logouts)
        assertNull(engine.state.value.account)
    }

    @Test fun given_signed_in_when_signing_out_then_the_token_is_wiped_and_cosmos_is_told() = syncTest { setup ->
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
        assertFalse("refresh-" in CosmosCredentials("refresh-9", "User", "u").toString())
        assertFalse("access-" in CosmosSession("access-9", 1, "refresh-9", null, CosmosUser("u", "User")).toString())
    }

    @Test fun given_signed_out_when_a_run_finishes_then_nothing_is_uploaded_but_the_count_updates() = syncTest { setup ->
        setup.outbox.add("r1")
        setup.engine.start()
        setup.engine.uploadRunLogs()
        advanceUntilIdle()
        assertTrue(setup.api.uploads.isEmpty())
        assertEquals(1, setup.engine.state.value.runLogsWaiting)
    }

    @Test fun given_signed_in_when_runs_finish_then_each_is_uploaded_once_and_marked_sent() = syncTest { setup ->
        setup.outbox.add("r1")
        setup.outbox.add("r2")
        setup.api.uploadStatus = { if (it.contains("\"r2\"")) "duplicate" else "stored" }
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(2, setup.api.uploads.size)
        assertEquals(mapOf("r1" to RunLogMark.Sent, "r2" to RunLogMark.Sent), setup.outbox.marks)
        assertEquals(0, setup.engine.state.value.runLogsWaiting)
        setup.engine.uploadRunLogs()
        advanceUntilIdle()
        assertEquals(2, setup.api.uploads.size)
    }

    @Test fun given_cosmos_refuses_one_run_when_uploading_then_it_is_set_aside_and_the_others_still_go() = syncTest { setup ->
        setup.outbox.add("bad")
        setup.outbox.add("good")
        setup.outbox.runs["broken"] = listOf("not json")
        setup.api.uploadFailures += CosmosApiException.Rejected(413)
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals(mapOf("bad" to RunLogMark.Rejected, "good" to RunLogMark.Sent, "broken" to RunLogMark.Unreadable), setup.outbox.marks)
    }

    @Test fun given_cosmos_is_unreachable_when_uploading_then_it_waits_and_retries_later() = syncTest { setup ->
        setup.outbox.add("r1")
        // Three failed attempts (with short back-off) end this upload; the next try is a minute later.
        setup.api.uploadFailures += List(3) { CosmosApiException.Offline() }
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        // Still backing off between attempts.
        advanceTimeBy(5_000)
        assertTrue(setup.outbox.marks.isEmpty())
        assertEquals(1, setup.engine.state.value.runLogsWaiting)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(RunLogMark.Sent, setup.outbox.marks["r1"])
    }

    private fun hex64(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }

    @Test fun given_a_sign_in_then_only_the_hashed_machine_id_and_platform_are_sent() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        val (machineId, platform) = setup.api.logins.single()
        assertEquals(MachineIdentity.hash("TEST-PLATFORM-UUID"), machineId)
        assertTrue(hex64(machineId))
        assertEquals("macos", platform)
    }

    @Test fun given_no_machine_identifier_when_signing_in_then_nothing_is_sent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val api = FakeApi()
            val engine = CosmosSyncEngine(api, FakeStore(), FakeCredentials(), scope, now = { 0 }, deviceName = "AutoPilot on test")
            assertEquals(CosmosSignInResult.ComputerNotIdentified, engine.signIn("person@example.com", "test-password"))
            assertTrue(api.logins.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun given_each_machine_refusal_when_signing_in_then_it_maps_to_its_own_result() = syncTest { setup ->
        setup.engine.start()
        val cases = mapOf(
            CosmosApiException.MACHINE_PENDING to CosmosSignInResult.MachinePending,
            CosmosApiException.MACHINE_REVOKED to CosmosSignInResult.MachineRevoked,
            CosmosApiException.ACCOUNT_DISABLED to CosmosSignInResult.AccountDisabled,
            "SOMETHING_NEW" to CosmosSignInResult.ServerTrouble,
        )
        cases.forEach { (code, expected) ->
            setup.api.loginFails = CosmosApiException.Forbidden(code)
            assertEquals(expected, setup.engine.signIn("person@example.com", "test-password"), code)
        }
        assertNull(setup.engine.state.value.account)
        assertNull(setup.credentials.value.value)
        // Try again once the admin approved this computer.
        setup.api.loginFails = null
        assertEquals(CosmosSignInResult.SignedIn, setup.engine.signIn("person@example.com", "test-password"))
    }

    @Test fun given_a_temporary_password_when_signed_in_then_nothing_syncs_until_it_is_changed() = syncTest { setup ->
        setup.api.mustChange = true
        setup.api.pages["smy"] = mutableListOf(CosmosEntriesPage(listOf(entry("e1", 5)), 5, false))
        setup.engine.start()
        assertEquals(CosmosSignInResult.SignedIn, setup.engine.signIn("person@example.com", "temporary-pass"))
        advanceUntilIdle()
        assertTrue(setup.engine.state.value.passwordChangeRequired)
        assertTrue(setup.credentials.value.value!!.mustChangePassword)
        assertTrue(setup.store.applied.isEmpty())

        assertEquals(com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.TooShort, setup.engine.changePassword("temporary-pass", "short"))
        assertTrue(setup.api.passwordChanges.isEmpty())
        assertEquals(com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Changed, setup.engine.changePassword("temporary-pass", "a-much-longer-password"))
        advanceUntilIdle()
        assertEquals(listOf("temporary-pass" to "a-much-longer-password"), setup.api.passwordChanges)
        assertFalse(setup.engine.state.value.passwordChangeRequired)
        assertFalse(setup.credentials.value.value!!.mustChangePassword)
        assertEquals(listOf("e1"), setup.store.applied.map { it.id })
        // Neither password is kept or logged.
        assertFalse(setup.credentials.value.value.toString().contains("password"))
        assertTrue(setup.logs.none { "temporary-pass" in it || "a-much-longer-password" in it })
    }

    @Test fun given_a_wrong_current_password_when_changing_then_the_session_stays() = syncTest { setup ->
        setup.api.mustChange = true
        setup.engine.start()
        setup.engine.signIn("person@example.com", "temporary-pass")
        setup.api.changeFailures += CosmosApiException.Rejected(400)
        assertEquals(com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.WrongCurrentPassword, setup.engine.changePassword("not-it", "a-much-longer-password"))
        setup.api.changeFailures += CosmosApiException.Offline()
        assertEquals(com.jagdeep.shared.cosmos.CosmosPasswordChangeResult.Offline, setup.engine.changePassword("temporary-pass", "a-much-longer-password"))
        assertTrue(setup.engine.state.value.passwordChangeRequired)
        assertEquals("Test User", setup.engine.state.value.account?.name)
    }

    @Test fun given_cosmos_requires_a_new_password_during_sync_then_sync_stops_and_the_form_is_asked_for() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += CosmosApiException.Forbidden(CosmosApiException.PASSWORD_CHANGE_REQUIRED)
        setup.api.failures += CosmosApiException.Offline()
        advanceUntilIdle()
        assertTrue(setup.engine.state.value.passwordChangeRequired)
        assertTrue(setup.credentials.value.value!!.mustChangePassword)
        assertEquals("Test User", setup.engine.state.value.account?.name)
        // No retry loop while the password is temporary: the next queued answer is never asked for.
        setup.engine.syncNow()
        advanceTimeBy(2 * 60 * 60_000L)
        advanceUntilIdle()
        assertEquals(1, setup.api.failures.size)
    }

    @Test fun given_the_machine_is_revoked_during_sync_then_the_session_ends_and_says_why() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        setup.api.failures += CosmosApiException.Forbidden(CosmosApiException.MACHINE_REVOKED)
        advanceUntilIdle()
        val state = setup.engine.state.value
        assertNull(state.account)
        assertTrue(state.signInExpired)
        assertEquals(com.jagdeep.shared.cosmos.CosmosSessionEnd.MachineRevoked, state.sessionEnd)
        assertNull(setup.credentials.value.value)
    }

    @Test fun given_a_disabled_account_at_refresh_then_the_session_ends_and_says_why() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.api.validAccess = "rotated"
        setup.api.refreshFailures += CosmosApiException.Forbidden(CosmosApiException.ACCOUNT_DISABLED)
        setup.engine.syncNow()
        advanceUntilIdle()
        assertEquals(com.jagdeep.shared.cosmos.CosmosSessionEnd.AccountDisabled, setup.engine.state.value.sessionEnd)
        assertNull(setup.engine.state.value.account)
    }

    @Test fun given_signed_in_when_asking_for_a_browser_link_then_one_request_returns_it_and_nothing_logs_it() = syncTest { setup ->
        setup.engine.start()
        assertEquals(com.jagdeep.shared.cosmos.CosmosBrowserLinkResult.Failed(com.jagdeep.shared.cosmos.CosmosProblem.SignInNeeded), setup.engine.browserLink())
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        val link = setup.engine.browserLink() as com.jagdeep.shared.cosmos.CosmosBrowserLinkResult.Link
        assertEquals("https://cosmos.test/link#secret-link-token", link.url)
        assertFalse(link.toString().contains("secret"))
        setup.api.linkFailures += CosmosApiException.Offline()
        assertEquals(com.jagdeep.shared.cosmos.CosmosBrowserLinkResult.Failed(com.jagdeep.shared.cosmos.CosmosProblem.Offline), setup.engine.browserLink())
        // A single-use link is never requested twice for one press.
        assertEquals(1, setup.api.browserLinks)
        assertTrue(setup.logs.none { "secret" in it })
    }

    @Test fun given_the_admin_when_creating_an_account_then_the_temporary_password_comes_back_once_and_isnt_logged() = syncTest { setup ->
        setup.api.role = "admin"
        setup.engine.start()
        setup.engine.signIn("admin@example.com", "test-password")
        advanceUntilIdle()
        assertTrue(setup.engine.state.value.account!!.isAdmin)
        assertEquals("admin", setup.credentials.value.value?.role)
        val result = setup.engine.admin.createAccount(" Asha ", " asha@example.com ", 2) as com.jagdeep.shared.cosmos.CosmosAdminResult.Done
        assertEquals("temp-secret-123", result.value.temporaryPassword.value)
        assertEquals(CosmosCreateAccountRequest("Asha", "asha@example.com", 2), setup.api.created.single())
        assertFalse(result.toString().contains("temp-secret-123"))
        assertTrue(setup.logs.none { "temp-secret-123" in it })
        // A write is never retried, so a lost reply can't create the account twice.
        setup.api.createFailure = CosmosApiException.Offline()
        assertEquals(com.jagdeep.shared.cosmos.CosmosAdminResult.Failed(com.jagdeep.shared.cosmos.CosmosProblem.Offline), setup.engine.admin.createAccount("B", "b@example.com", 1))
        assertEquals(1, setup.api.created.size)
    }

    @Test fun given_an_approval_beyond_the_limit_then_it_is_reported_as_a_conflict() = syncTest { setup ->
        setup.api.role = "admin"
        setup.engine.start()
        setup.engine.signIn("admin@example.com", "test-password")
        advanceUntilIdle()
        setup.api.approveFailure = CosmosApiException.Conflict(null)
        assertEquals(com.jagdeep.shared.cosmos.CosmosAdminResult.Failed(com.jagdeep.shared.cosmos.CosmosProblem.Conflict), setup.engine.admin.approveMachine("m_1"))
        setup.api.approveFailure = null
        val approved = setup.engine.admin.approveMachine("m_1") as com.jagdeep.shared.cosmos.CosmosAdminResult.Done
        assertEquals(com.jagdeep.shared.cosmos.CosmosMachineStatus.Approved, approved.value.status)
        assertEquals("Asha", approved.value.accountName)
    }

    @Test fun given_signed_out_when_using_admin_then_nothing_is_requested() = syncTest { setup ->
        setup.engine.start()
        assertEquals(com.jagdeep.shared.cosmos.CosmosAdminResult.Failed(com.jagdeep.shared.cosmos.CosmosProblem.SignInNeeded), setup.engine.admin.approveMachine("m_1"))
        assertEquals(0, setup.api.approvals)
    }

    @Test fun given_a_whole_session_then_logs_never_hold_the_machine_id_or_its_source() = syncTest { setup ->
        setup.engine.start()
        setup.engine.signIn("person@example.com", "test-password")
        advanceUntilIdle()
        setup.engine.signOut()
        advanceUntilIdle()
        val hash = MachineIdentity.hash("TEST-PLATFORM-UUID")
        assertTrue(setup.logs.isNotEmpty())
        assertTrue(setup.logs.none { hash in it || "TEST-PLATFORM-UUID" in it || "test-password" in it })
    }
}
