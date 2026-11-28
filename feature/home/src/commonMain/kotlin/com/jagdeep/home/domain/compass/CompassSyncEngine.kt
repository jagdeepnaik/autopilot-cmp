package com.jagdeep.home.domain.compass

import com.jagdeep.persistence.preferences.SecurePreferencesStore
import com.jagdeep.shared.compass.CompassAccount
import com.jagdeep.shared.compass.CompassSignInResult
import com.jagdeep.shared.compass.CompassSync
import com.jagdeep.shared.compass.CompassSyncOutcome
import com.jagdeep.shared.compass.CompassSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads approved Compass answers into the routine Q&A files and uploads the answers the user
 * confirmed. Opt-in and offline-first: it does nothing until the user signs in, every failure just
 * waits for the next attempt, and it never touches a running routine (routines keep reading the same
 * files, which only ever gain exact, approved entries).
 *
 * The access token lives only in memory; the refresh token only in [credentials] (platform secure
 * storage). Neither is ever logged.
 */
class CompassSyncEngine(
    private val api: CompassApi,
    private val local: CompassQaStore,
    private val credentials: SecurePreferencesStore<CompassCredentials>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val debounceMillis: Long = 10_000,
    private val retryLaterMillis: List<Long> = listOf(60_000, 5 * 60_000, 15 * 60_000, 60 * 60_000),
    /** Finished routine run logs to upload (diagnostics contract); none on platforms without them. */
    private val runLogs: RunLogOutbox = NoRunLogOutbox,
) : CompassSync {
    private val mutableState = MutableStateFlow(CompassSyncState(available = true))
    override val state: StateFlow<CompassSyncState> = mutableState.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val tokenMutex = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt = 0L
    private var currentSync: Job? = null
    private var debounce: Job? = null
    private var retryLater: Job? = null
    private var failures = 0
    private var started = false
    private val runLogRequests = Channel<Unit>(Channel.CONFLATED)
    private var runLogUpload: Job? = null
    private var runLogRetry: Job? = null
    private var runLogFailures = 0

    /** Restores a saved session and syncs once; call once at app start. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (request in requests) {
                val job = scope.launch { syncOnce() }
                currentSync = job
                job.join()
            }
        }
        scope.launch {
            for (request in runLogRequests) {
                val job = scope.launch { uploadRunLogsOnce() }
                runLogUpload = job
                job.join()
            }
        }
        scope.launch {
            val saved = runCatching { credentials.data.first() }.getOrNull()
            refreshPendingCount()
            refreshRunLogCount()
            // A sign-in that finished first has already asked for its own sync.
            if (saved != null && state.value.account == null) {
                mutableState.update { it.copy(account = CompassAccount(saved.userName)) }
                syncNow()
            }
            // Runs that ended while the app was closed, or while signed out.
            if (saved != null) uploadRunLogs()
        }
    }

    override suspend fun signIn(email: String, password: String): CompassSignInResult {
        val session = try { api.login(email.trim(), password, deviceName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CompassApiException) { return when (error) {
            is CompassApiException.Unauthorized, is CompassApiException.Rejected -> CompassSignInResult.WrongEmailOrPassword
            is CompassApiException.Offline -> CompassSignInResult.Offline
            is CompassApiException.RateLimited -> CompassSignInResult.RateLimited
            is CompassApiException.ServerTrouble, is CompassApiException.NotFound -> CompassSignInResult.ServerTrouble
        } }
        catch (_: Exception) { return CompassSignInResult.ServerTrouble }
        val saved = tokenMutex.withLock {
            try {
                credentials.save(CompassCredentials(session.refreshToken, session.user.name, session.user.id, session.refreshTokenExpiresAt))
                remember(session)
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        if (!saved) {
            // Without secure storage the session can't be kept, so end it rather than leave it open.
            runCatching { api.logout(session.refreshToken) }
            return CompassSignInResult.SecureStorageUnavailable
        }
        failures = 0
        mutableState.update { it.copy(account = CompassAccount(session.user.name), signInExpired = false, lastOutcome = null) }
        log("Signed in to Compass")
        syncNow()
        uploadRunLogs()
        return CompassSignInResult.SignedIn
    }

    override suspend fun signOut() {
        retryLater?.cancel()
        debounce?.cancel()
        currentSync?.cancel()
        runLogRetry?.cancel()
        runLogUpload?.cancel()
        val saved = tokenMutex.withLock {
            val value = runCatching { credentials.data.first() }.getOrNull()
            accessToken = null
            accessExpiresAt = 0
            runCatching { credentials.clear() }
            value
        }
        mutableState.update { it.copy(account = null, syncing = false, signInExpired = false, lastOutcome = null, lastSyncAtMillis = null, lastDownloaded = 0, lastSent = 0) }
        log("Signed out of Compass")
        // Best effort: the token is already gone from this computer whether or not Compass is reachable.
        if (saved != null) scope.launch { runCatching { api.logout(saved.refreshToken) } }
    }

    override fun syncNow() { requests.trySend(Unit) }

    /**
     * Uploads finished run logs (after each run ends, and at start-up). Does nothing on the network
     * while signed out; the waiting count still updates. Never touches a running routine.
     */
    fun uploadRunLogs() { runLogRequests.trySend(Unit) }

    private suspend fun uploadRunLogsOnce() {
        if (runCatching { credentials.data.first() }.getOrNull() == null) { refreshRunLogCount(); return }
        runLogRetry?.cancel()
        var retry = false
        try {
            var rounds = 0
            uploads@ while (rounds++ < MAX_PAGES) {
                val ids = runLogs.unsent(RUN_LOG_BATCH)
                if (ids.isEmpty()) break
                for (id in ids) {
                    val lines = runLogs.lines(id)
                    val body = lines?.let { RunLogUploadBody.build(id, it) }
                    if (body == null) { runLogs.mark(id, RunLogMark.Unreadable); continue }
                    val status = try { authed { token -> api.uploadRun(token, body) } }
                    // Compass refused this run (malformed or too large): keep it here, but don't resend it forever.
                    catch (_: CompassApiException.Rejected) { runLogs.mark(id, RunLogMark.Rejected); continue }
                    if (status !in RunLogUploadBody.SENT) { retry = true; break@uploads }
                    runLogs.mark(id, RunLogMark.Sent)
                    refreshRunLogCount()
                }
                if (ids.size < RUN_LOG_BATCH) break
            }
            if (!retry) runLogFailures = 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SignInNeeded) { }
        catch (_: CompassApiException.NotFound) { }
        catch (_: CompassApiException) { retry = true }
        catch (_: StorageProblem) { retry = true }
        catch (_: Exception) { retry = true }
        refreshRunLogCount()
        if (retry && state.value.account != null) {
            val wait = retryLaterMillis[runLogFailures.coerceAtMost(retryLaterMillis.lastIndex)]
            runLogFailures++
            runLogRetry = scope.launch { delay(wait); uploadRunLogs() }
        }
    }

    private suspend fun refreshRunLogCount() {
        val count = runCatching { runLogs.waitingCount() }.getOrNull() ?: return
        mutableState.update { it.copy(runLogsWaiting = count) }
    }

    override fun answersChanged() {
        debounce?.cancel()
        debounce = scope.launch {
            refreshPendingCount()
            delay(debounceMillis)
            syncNow()
        }
    }

    private suspend fun syncOnce() {
        if (runCatching { credentials.data.first() }.getOrNull() == null) return
        retryLater?.cancel()
        mutableState.update { it.copy(syncing = true) }
        var downloaded = 0
        var sent = 0
        val outcome = try {
            for (bank in local.banks) {
                downloaded += pull(bank)
                sent += push(bank)
            }
            CompassSyncOutcome.Synced
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(syncing = false) }
            throw cancelled
        } catch (_: SignInNeeded) { CompassSyncOutcome.SignInNeeded }
        catch (_: StorageProblem) { CompassSyncOutcome.StorageProblem }
        catch (error: CompassApiException) { when (error) {
            is CompassApiException.Offline -> CompassSyncOutcome.Offline
            is CompassApiException.RateLimited -> CompassSyncOutcome.RateLimited
            else -> CompassSyncOutcome.ServerTrouble
        } }
        catch (_: Exception) { CompassSyncOutcome.ServerTrouble }
        val pending = runCatching { local.pendingCount() }.getOrDefault(state.value.pendingUploads)
        mutableState.update { current ->
            if (current.account == null && outcome == CompassSyncOutcome.SignInNeeded && !current.signInExpired) current.copy(syncing = false, pendingUploads = pending)
            else current.copy(
                syncing = false,
                lastOutcome = outcome,
                lastSyncAtMillis = if (outcome == CompassSyncOutcome.Synced) now() else current.lastSyncAtMillis,
                lastDownloaded = if (outcome == CompassSyncOutcome.Synced) downloaded else current.lastDownloaded,
                lastSent = if (outcome == CompassSyncOutcome.Synced) sent else current.lastSent,
                pendingUploads = pending,
            )
        }
        log(when (outcome) {
            CompassSyncOutcome.Synced -> "Compass sync: downloaded $downloaded, sent $sent"
            else -> "Compass sync stopped: $outcome"
        })
        if (outcome in RETRYABLE && state.value.account != null) {
            val wait = retryLaterMillis[failures.coerceAtMost(retryLaterMillis.lastIndex)]
            failures++
            retryLater = scope.launch { delay(wait); syncNow() }
        } else if (outcome == CompassSyncOutcome.Synced) failures = 0
    }

    private suspend fun pull(bank: String): Int {
        var after = local.lastRevision(bank) ?: throw StorageProblem()
        var count = 0
        repeat(MAX_PAGES) {
            val page = try { authed { token -> api.entries(token, bank, after, PAGE_SIZE) } }
            catch (_: CompassApiException.NotFound) { return count }
            val next = maxOf(after, page.nextAfter)
            if (!local.apply(bank, page.entries.filter { it.bank == bank }, next)) throw StorageProblem()
            count += page.entries.size
            if (!page.hasMore || next == after) return count
            after = next
        }
        return count
    }

    private suspend fun push(bank: String): Int {
        var sent = 0
        repeat(MAX_PAGES) {
            val pending = local.pending(bank, BATCH_SIZE) ?: throw StorageProblem()
            if (pending.isEmpty()) return sent
            val (fitting, unsendable) = pending.partition(CompassSubmission::fitsContract)
            if (unsendable.isNotEmpty() && !local.markSubmitted(bank, unsendable.associate { it.clientId to NOT_SENDABLE })) throw StorageProblem()
            // Answers whose keys Compass wouldn't accept stay pending here; they're never marked as given up.
            val sendable = fitting.filter(CompassSubmission::keysFitContract)
            if (sendable.isEmpty()) { if (unsendable.isEmpty()) return sent else return@repeat }
            val statuses = try { submitted(bank, sendable) }
            catch (_: CompassApiException.Rejected) { sendable.associate { one -> one.clientId to submitAlone(bank, one) } }
            if (!local.markSubmitted(bank, statuses)) throw StorageProblem()
            sent += statuses.values.count { it in CompassSubmissionResult.SENT }
            // An unexpected answer from Compass leaves the rest for a later sync instead of looping.
            if (statuses.size < sendable.size) return sent
        }
        return sent
    }

    private suspend fun submitted(bank: String, batch: List<CompassSubmission>): Map<String, String> {
        val ids = batch.map { it.clientId }.toSet()
        return authed { token -> api.submit(token, bank, batch) }
            .filter { it.clientId in ids && it.status in CompassSubmissionResult.SENT }
            .associate { it.clientId to it.status }
    }

    /** Finds which submission Compass refused, so one bad answer can't block every other upload. */
    private suspend fun submitAlone(bank: String, one: CompassSubmission): String =
        try { submitted(bank, listOf(one))[one.clientId] ?: REJECTED } catch (_: CompassApiException.Rejected) { REJECTED }

    /** Calls Compass with a valid access token; on 401 refreshes once and retries, then asks for sign-in. */
    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val token = validAccessToken()
        return try { retrying { block(token) } }
        catch (_: CompassApiException.Unauthorized) {
            val fresh = refreshAccessToken(rejected = token)
            try { retrying { block(fresh) } }
            catch (_: CompassApiException.Unauthorized) { expireSession(); throw SignInNeeded() }
        }
    }

    private suspend fun validAccessToken(): String =
        tokenMutex.withLock { accessToken?.takeIf { now() < accessExpiresAt } } ?: refreshAccessToken(rejected = null)

    private suspend fun refreshAccessToken(rejected: String?): String = tokenMutex.withLock {
        // Another call may already have refreshed while this one waited.
        accessToken?.takeIf { it != rejected && now() < accessExpiresAt }?.let { return@withLock it }
        val saved = runCatching { credentials.data.first() }.getOrNull() ?: throw SignInNeeded()
        // At most one retry with the same token: Compass accepts the token its latest refresh retired for
        // 60 seconds, so a lost reply can be retried once; a second retry would look like token reuse.
        val session = try { retrying(networkAttempts = REFRESH_ATTEMPTS) { api.refresh(saved.refreshToken) } }
        catch (_: CompassApiException.Unauthorized) { expireSessionLocked(); throw SignInNeeded() }
        // Refresh tokens rotate: keep the new one before using the session, or the next start fails.
        try { credentials.save(saved.copy(refreshToken = session.refreshToken, userName = session.user.name, userId = session.user.id, refreshTokenExpiresAt = session.refreshTokenExpiresAt)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw StorageProblem() }
        remember(session)
        mutableState.update { it.copy(account = CompassAccount(session.user.name)) }
        session.accessToken
    }

    private fun remember(session: CompassSession) {
        accessToken = session.accessToken
        accessExpiresAt = now() + session.accessTokenExpiresIn.coerceAtLeast(0) * 1_000 - EXPIRY_MARGIN_MILLIS
    }

    private suspend fun expireSession() = tokenMutex.withLock { expireSessionLocked() }

    private suspend fun expireSessionLocked() {
        accessToken = null
        accessExpiresAt = 0
        runCatching { credentials.clear() }
        mutableState.update { it.copy(account = null, signInExpired = true) }
        log("Compass session ended; sign in again in Settings")
    }

    /** Retries network and server failures with back-off, and waits as long as Compass asks after 429. */
    private suspend fun <T> retrying(networkAttempts: Int = ATTEMPTS, block: suspend () -> T): T {
        var failed = 0
        var limited = 0
        while (true) {
            try { return block() }
            catch (error: CompassApiException.Offline) { if (++failed >= networkAttempts) throw error; sleep(BACKOFF_MILLIS shl (failed - 1)) }
            catch (error: CompassApiException.ServerTrouble) { if (++failed >= networkAttempts) throw error; sleep(BACKOFF_MILLIS shl (failed - 1)) }
            catch (error: CompassApiException.RateLimited) {
                if (++limited > RATE_LIMIT_WAITS) throw error
                sleep((error.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1, MAX_RETRY_AFTER_SECONDS) * 1_000)
            }
        }
    }

    private suspend fun refreshPendingCount() {
        val count = runCatching { local.pendingCount() }.getOrNull() ?: return
        mutableState.update { it.copy(pendingUploads = count) }
    }

    private class SignInNeeded : Exception()
    private class StorageProblem : Exception()

    private companion object {
        const val PAGE_SIZE = 500
        const val BATCH_SIZE = 100
        const val RUN_LOG_BATCH = 20
        const val MAX_PAGES = 1_000
        const val ATTEMPTS = 3
        const val REFRESH_ATTEMPTS = 2
        const val BACKOFF_MILLIS = 2_000L
        const val RATE_LIMIT_WAITS = 2
        const val DEFAULT_RETRY_AFTER_SECONDS = 30L
        const val MAX_RETRY_AFTER_SECONDS = 300L
        const val EXPIRY_MARGIN_MILLIS = 30_000L
        const val NOT_SENDABLE = "not_sendable"
        const val REJECTED = "rejected"
        val RETRYABLE = setOf(CompassSyncOutcome.Offline, CompassSyncOutcome.ServerTrouble, CompassSyncOutcome.RateLimited)
    }
}
