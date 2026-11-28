package com.jagdeep.home.domain.cosmos

import com.jagdeep.persistence.preferences.SecurePreferencesStore
import com.jagdeep.shared.cosmos.CosmosAccount
import com.jagdeep.shared.cosmos.CosmosAccountQuery
import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosAdminResult
import com.jagdeep.shared.cosmos.CosmosBrowserLinkResult
import com.jagdeep.shared.cosmos.CosmosCreatedAccount
import com.jagdeep.shared.cosmos.CosmosMachineStatus
import com.jagdeep.shared.cosmos.CosmosPage
import com.jagdeep.shared.cosmos.CosmosPasswordChangeResult
import com.jagdeep.shared.cosmos.CosmosProblem
import com.jagdeep.shared.cosmos.CosmosSessionEnd
import com.jagdeep.shared.cosmos.CosmosSignInResult
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncOutcome
import com.jagdeep.shared.cosmos.CosmosSyncState
import com.jagdeep.shared.cosmos.CosmosTemporaryPassword
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

/**
 * Downloads approved Cosmos answers into the routine Q&A files and uploads the answers the user
 * confirmed. Opt-in and offline-first: it does nothing until the user signs in, every failure just
 * waits for the next attempt, and it never touches a running routine (routines keep reading the same
 * files, which only ever gain exact, approved entries).
 *
 * The access token lives only in memory; the refresh token only in [credentials] (platform secure
 * storage). Neither is ever logged, and nor are this computer's machine ID, one-time browser links or
 * the temporary passwords the admin creates.
 */
class CosmosSyncEngine(
    private val api: CosmosApi,
    private val local: CosmosQaStore,
    private val credentials: SecurePreferencesStore<CosmosCredentials>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val deviceName: String,
    private val log: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val debounceMillis: Long = 10_000,
    private val retryLaterMillis: List<Long> = listOf(60_000, 5 * 60_000, 15 * 60_000, 60 * 60_000),
    /** Finished routine run logs to upload (diagnostics contract); none on platforms without them. */
    private val runLogs: RunLogOutbox = NoRunLogOutbox,
    /** This computer's identifier; only its hash ([MachineIdentity]) is sent, with every sign-in. */
    private val machine: MachineIdentifierSource = NoMachineIdentifier,
) : CosmosSync {
    private val mutableState = MutableStateFlow(CosmosSyncState(available = true))
    override val state: StateFlow<CosmosSyncState> = mutableState.asStateFlow()

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
    private var machineIdentity: MachineIdentity? = null

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
                mutableState.update { it.copy(account = CosmosAccount(saved.userName, saved.role), passwordChangeRequired = saved.mustChangePassword) }
                syncNow()
            }
            // Runs that ended while the app was closed, or while signed out.
            if (saved != null) uploadRunLogs()
        }
    }

    override suspend fun signIn(email: String, password: String): CosmosSignInResult {
        val identity = identity() ?: return CosmosSignInResult.ComputerNotIdentified
        val session = try { api.login(email.trim(), password, deviceName, identity.machineId, identity.platform) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CosmosApiException) { return when (error) {
            is CosmosApiException.Unauthorized, is CosmosApiException.Rejected -> CosmosSignInResult.WrongEmailOrPassword
            is CosmosApiException.Offline -> CosmosSignInResult.Offline
            is CosmosApiException.RateLimited -> CosmosSignInResult.RateLimited
            is CosmosApiException.Forbidden -> when (error.code) {
                CosmosApiException.MACHINE_PENDING -> CosmosSignInResult.MachinePending
                CosmosApiException.MACHINE_REVOKED -> CosmosSignInResult.MachineRevoked
                CosmosApiException.ACCOUNT_DISABLED -> CosmosSignInResult.AccountDisabled
                else -> CosmosSignInResult.ServerTrouble
            }
            is CosmosApiException.ServerTrouble, is CosmosApiException.NotFound, is CosmosApiException.Conflict -> CosmosSignInResult.ServerTrouble
        } }
        catch (_: Exception) { return CosmosSignInResult.ServerTrouble }
        val saved = tokenMutex.withLock {
            try {
                credentials.save(CosmosCredentials(session.refreshToken, session.user.name, session.user.id, session.refreshTokenExpiresAt, session.user.role, session.mustChangePassword))
                remember(session)
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        if (!saved) {
            // Without secure storage the session can't be kept, so end it rather than leave it open.
            runCatching { api.logout(session.refreshToken) }
            return CosmosSignInResult.SecureStorageUnavailable
        }
        failures = 0
        mutableState.update { it.copy(account = CosmosAccount(session.user.name, session.user.role), signInExpired = false, sessionEnd = null,
            lastOutcome = null, passwordChangeRequired = session.mustChangePassword) }
        if (session.mustChangePassword) {
            log("Signed in to Cosmos; a new password is needed before syncing")
            return CosmosSignInResult.SignedIn
        }
        log("Signed in to Cosmos")
        syncNow()
        uploadRunLogs()
        return CosmosSignInResult.SignedIn
    }

    /** Hashes this computer's identifier once (it may start a short system command, so off the caller's thread). */
    private suspend fun identity(): MachineIdentity? = machineIdentity ?: withContext(Dispatchers.Default) { MachineIdentity.of(machine) }?.also { machineIdentity = it }

    override suspend fun changePassword(currentPassword: String, newPassword: String): CosmosPasswordChangeResult {
        if (newPassword.length < CosmosSync.MIN_PASSWORD_LENGTH) return CosmosPasswordChangeResult.TooShort
        if (currentPassword.isEmpty()) return CosmosPasswordChangeResult.WrongCurrentPassword
        if (state.value.account == null) return CosmosPasswordChangeResult.SignInNeeded
        suspend fun once(token: String) = retrying(networkAttempts = 1) { api.changePassword(token, currentPassword, newPassword) }
        val reply = try {
            val token = validAccessToken()
            try { once(token) }
            catch (_: CosmosApiException.Unauthorized) {
                // The access token may have just been revoked: refresh once. A second 401 is the current password.
                val fresh = refreshAccessToken(rejected = token)
                try { once(fresh) } catch (_: CosmosApiException.Unauthorized) { return CosmosPasswordChangeResult.WrongCurrentPassword }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SignInNeeded) { return CosmosPasswordChangeResult.SignInNeeded }
        catch (error: CosmosApiException.Forbidden) {
            if (endsSession(error.code) != null) { endSession(error.code); return CosmosPasswordChangeResult.SignInNeeded }
            return CosmosPasswordChangeResult.WrongCurrentPassword
        }
        catch (_: CosmosApiException.Rejected) { return CosmosPasswordChangeResult.WrongCurrentPassword }
        catch (_: CosmosApiException.Offline) { return CosmosPasswordChangeResult.Offline }
        catch (_: CosmosApiException.RateLimited) { return CosmosPasswordChangeResult.RateLimited }
        catch (_: Exception) { return CosmosPasswordChangeResult.ServerTrouble }
        val stored = tokenMutex.withLock {
            try {
                val saved = credentials.data.first() ?: return@withLock false
                if (reply != null) {
                    credentials.save(saved.copy(refreshToken = reply.refreshToken, userName = reply.user.name, userId = reply.user.id,
                        refreshTokenExpiresAt = reply.refreshTokenExpiresAt, role = reply.user.role ?: saved.role, mustChangePassword = false))
                    remember(reply)
                } else {
                    credentials.save(saved.copy(mustChangePassword = false))
                    // The old access token may still carry the temporary-password limit: get a fresh one next time.
                    accessToken = null
                    accessExpiresAt = 0
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        mutableState.update { it.copy(passwordChangeRequired = false) }
        log("Changed the Cosmos password")
        if (!stored) log("Couldn't update the saved Cosmos session; sign in again if syncing stops")
        syncNow()
        uploadRunLogs()
        return CosmosPasswordChangeResult.Changed
    }

    override suspend fun browserLink(): CosmosBrowserLinkResult {
        if (state.value.account == null) return CosmosBrowserLinkResult.Failed(CosmosProblem.SignInNeeded)
        if (state.value.passwordChangeRequired) return CosmosBrowserLinkResult.Failed(CosmosProblem.NotAllowed)
        // One attempt only: each link is single-use, and a retried request would only mint another.
        return try { CosmosBrowserLinkResult.Link(authed(networkAttempts = 1) { token -> api.browserLink(token) }.url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CosmosBrowserLinkResult.Failed(problemOf(error)) }
    }

    override val admin: CosmosAdmin = object : CosmosAdmin {
        override suspend fun accounts(query: CosmosAccountQuery) = adminCall {
            val page = authed { token -> api.adminAccounts(token, query.status?.wire, query.search.trim().takeIf { it.isNotEmpty() }, query.page, query.pageSize) }
            CosmosPage(page.accounts.map(::account), page.page, page.pageSize, page.total)
        }

        override suspend fun createAccount(name: String, email: String, machineLimit: Int) = adminCall {
            val created = authed(networkAttempts = 1) { token -> api.adminCreateAccount(token, CosmosCreateAccountRequest(name.trim(), email.trim(), machineLimit)) }
            CosmosCreatedAccount(account(created.account), CosmosTemporaryPassword(created.temporaryPassword))
        }

        override suspend fun updateAccount(id: String, name: String?, machineLimit: Int?, status: CosmosAccountStatus?) = adminCall {
            account(authed(networkAttempts = 1) { token -> api.adminUpdateAccount(token, id, CosmosUpdateAccountRequest(name?.trim(), machineLimit, status?.wire)) })
        }

        override suspend fun resetPassword(id: String) = adminCall {
            CosmosTemporaryPassword(authed(networkAttempts = 1) { token -> api.adminResetPassword(token, id) })
        }

        override suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int) = adminCall {
            val result = authed { token -> api.adminMachines(token, status?.wire, page, pageSize) }
            CosmosPage(result.machines.map(::machine), result.page, result.pageSize, result.total)
        }

        override suspend fun approveMachine(id: String) = adminCall { machine(authed(networkAttempts = 1) { token -> api.adminApproveMachine(token, id) }) }

        override suspend fun revokeMachine(id: String) = adminCall { machine(authed(networkAttempts = 1) { token -> api.adminRevokeMachine(token, id) }) }
    }

    private suspend fun <T> adminCall(block: suspend () -> T): CosmosAdminResult<T> {
        if (state.value.account == null) return CosmosAdminResult.Failed(CosmosProblem.SignInNeeded)
        if (state.value.passwordChangeRequired) return CosmosAdminResult.Failed(CosmosProblem.NotAllowed)
        return try { CosmosAdminResult.Done(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CosmosAdminResult.Failed(problemOf(error)) }
    }

    private fun problemOf(error: Exception): CosmosProblem = when (error) {
        is SignInNeeded, is CosmosApiException.Unauthorized -> CosmosProblem.SignInNeeded
        is PasswordChangeNeeded, is CosmosApiException.Forbidden -> CosmosProblem.NotAllowed
        is CosmosApiException.Offline -> CosmosProblem.Offline
        is CosmosApiException.RateLimited -> CosmosProblem.RateLimited
        is CosmosApiException.NotFound -> CosmosProblem.NotFound
        is CosmosApiException.Conflict -> CosmosProblem.Conflict
        is CosmosApiException.Rejected -> CosmosProblem.Invalid
        else -> CosmosProblem.ServerTrouble
    }

    private fun account(wire: CosmosAccountWire) = CosmosAdminAccount(wire.id, wire.name, wire.email, wire.role, CosmosAccountStatus.of(wire.status),
        wire.machineLimit, wire.approvedMachines, wire.pendingMachines, wire.mustChangePassword, wire.createdAt, wire.lastSignInAt)

    private fun machine(wire: CosmosMachineWire) = CosmosAdminMachine(wire.id, wire.label, wire.platform, CosmosMachineStatus.of(wire.status),
        wire.firstSeenAt, wire.lastSeenAt, wire.approvedAt, wire.account?.id, wire.account?.name, wire.account?.email)

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
        mutableState.update { it.copy(account = null, syncing = false, signInExpired = false, sessionEnd = null, passwordChangeRequired = false, lastOutcome = null, lastSyncAtMillis = null, lastDownloaded = 0, lastSent = 0) }
        log("Signed out of Cosmos")
        // Best effort: the token is already gone from this computer whether or not Cosmos is reachable.
        if (saved != null) scope.launch { runCatching { api.logout(saved.refreshToken) } }
    }

    override fun syncNow() { requests.trySend(Unit) }

    /**
     * Uploads finished run logs (after each run ends, and at start-up). Does nothing on the network
     * while signed out; the waiting count still updates. Never touches a running routine.
     */
    fun uploadRunLogs() { runLogRequests.trySend(Unit) }

    private suspend fun uploadRunLogsOnce() {
        if (runCatching { credentials.data.first() }.getOrNull() == null || state.value.passwordChangeRequired) { refreshRunLogCount(); return }
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
                    // Cosmos refused this run (malformed or too large): keep it here, but don't resend it forever.
                    catch (_: CosmosApiException.Rejected) { runLogs.mark(id, RunLogMark.Rejected); continue }
                    if (status !in RunLogUploadBody.SENT) { retry = true; break@uploads }
                    runLogs.mark(id, RunLogMark.Sent)
                    refreshRunLogCount()
                }
                if (ids.size < RUN_LOG_BATCH) break
            }
            if (!retry) runLogFailures = 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SignInNeeded) { }
        catch (_: PasswordChangeNeeded) { }
        catch (_: CosmosApiException.NotFound) { }
        catch (_: CosmosApiException) { retry = true }
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
        if (runCatching { credentials.data.first() }.getOrNull() == null || state.value.passwordChangeRequired) return
        retryLater?.cancel()
        mutableState.update { it.copy(syncing = true) }
        var downloaded = 0
        var sent = 0
        val outcome = try {
            for (bank in local.banks) {
                downloaded += pull(bank)
                sent += push(bank)
            }
            CosmosSyncOutcome.Synced
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(syncing = false) }
            throw cancelled
        } catch (_: SignInNeeded) { CosmosSyncOutcome.SignInNeeded }
        catch (_: PasswordChangeNeeded) { CosmosSyncOutcome.SignInNeeded }
        catch (_: StorageProblem) { CosmosSyncOutcome.StorageProblem }
        catch (error: CosmosApiException) { when (error) {
            is CosmosApiException.Offline -> CosmosSyncOutcome.Offline
            is CosmosApiException.RateLimited -> CosmosSyncOutcome.RateLimited
            else -> CosmosSyncOutcome.ServerTrouble
        } }
        catch (_: Exception) { CosmosSyncOutcome.ServerTrouble }
        val pending = runCatching { local.pendingCount() }.getOrDefault(state.value.pendingUploads)
        mutableState.update { current ->
            if (current.account == null && outcome == CosmosSyncOutcome.SignInNeeded && !current.signInExpired) current.copy(syncing = false, pendingUploads = pending)
            else current.copy(
                syncing = false,
                lastOutcome = outcome,
                lastSyncAtMillis = if (outcome == CosmosSyncOutcome.Synced) now() else current.lastSyncAtMillis,
                lastDownloaded = if (outcome == CosmosSyncOutcome.Synced) downloaded else current.lastDownloaded,
                lastSent = if (outcome == CosmosSyncOutcome.Synced) sent else current.lastSent,
                pendingUploads = pending,
            )
        }
        log(when (outcome) {
            CosmosSyncOutcome.Synced -> "Cosmos sync: downloaded $downloaded, sent $sent"
            else -> "Cosmos sync stopped: $outcome"
        })
        if (outcome in RETRYABLE && state.value.account != null) {
            val wait = retryLaterMillis[failures.coerceAtMost(retryLaterMillis.lastIndex)]
            failures++
            retryLater = scope.launch { delay(wait); syncNow() }
        } else if (outcome == CosmosSyncOutcome.Synced) failures = 0
    }

    private suspend fun pull(bank: String): Int {
        var after = local.lastRevision(bank) ?: throw StorageProblem()
        var count = 0
        repeat(MAX_PAGES) {
            val page = try { authed { token -> api.entries(token, bank, after, PAGE_SIZE) } }
            catch (_: CosmosApiException.NotFound) { return count }
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
            val (fitting, unsendable) = pending.partition(CosmosSubmission::fitsContract)
            if (unsendable.isNotEmpty() && !local.markSubmitted(bank, unsendable.associate { it.clientId to NOT_SENDABLE })) throw StorageProblem()
            // Answers whose keys Cosmos wouldn't accept stay pending here; they're never marked as given up.
            val sendable = fitting.filter(CosmosSubmission::keysFitContract)
            if (sendable.isEmpty()) { if (unsendable.isEmpty()) return sent else return@repeat }
            val statuses = try { submitted(bank, sendable) }
            catch (_: CosmosApiException.Rejected) { sendable.associate { one -> one.clientId to submitAlone(bank, one) } }
            if (!local.markSubmitted(bank, statuses)) throw StorageProblem()
            sent += statuses.values.count { it in CosmosSubmissionResult.SENT }
            // An unexpected answer from Cosmos leaves the rest for a later sync instead of looping.
            if (statuses.size < sendable.size) return sent
        }
        return sent
    }

    private suspend fun submitted(bank: String, batch: List<CosmosSubmission>): Map<String, String> {
        val ids = batch.map { it.clientId }.toSet()
        return authed { token -> api.submit(token, bank, batch) }
            .filter { it.clientId in ids && it.status in CosmosSubmissionResult.SENT }
            .associate { it.clientId to it.status }
    }

    /** Finds which submission Cosmos refused, so one bad answer can't block every other upload. */
    private suspend fun submitAlone(bank: String, one: CosmosSubmission): String =
        try { submitted(bank, listOf(one))[one.clientId] ?: REJECTED } catch (_: CosmosApiException.Rejected) { REJECTED }

    /**
     * Calls Cosmos with a valid access token; on 401 refreshes once and retries, then asks for sign-in.
     * A revoked machine or disabled account ends the session; a temporary password stops syncing until it's changed.
     */
    private suspend fun <T> authed(networkAttempts: Int = ATTEMPTS, block: suspend (String) -> T): T {
        val token = validAccessToken()
        return try {
            try { retrying(networkAttempts) { block(token) } }
            catch (_: CosmosApiException.Unauthorized) {
                val fresh = refreshAccessToken(rejected = token)
                try { retrying(networkAttempts) { block(fresh) } }
                catch (_: CosmosApiException.Unauthorized) { expireSession(); throw SignInNeeded() }
            }
        } catch (error: CosmosApiException.Forbidden) {
            when {
                error.code == CosmosApiException.PASSWORD_CHANGE_REQUIRED -> { needPasswordChange(); throw PasswordChangeNeeded() }
                endsSession(error.code) != null -> { endSession(error.code); throw SignInNeeded() }
                else -> throw error
            }
        }
    }

    private fun endsSession(code: String?): CosmosSessionEnd? = when (code) {
        CosmosApiException.MACHINE_REVOKED, CosmosApiException.MACHINE_PENDING -> CosmosSessionEnd.MachineRevoked
        CosmosApiException.ACCOUNT_DISABLED -> CosmosSessionEnd.AccountDisabled
        else -> null
    }

    private suspend fun endSession(code: String?) = tokenMutex.withLock { expireSessionLocked(endsSession(code) ?: CosmosSessionEnd.Expired) }

    private suspend fun needPasswordChange() {
        tokenMutex.withLock { runCatching { credentials.data.first()?.let { credentials.save(it.copy(mustChangePassword = true)) } } }
        mutableState.update { it.copy(passwordChangeRequired = true) }
    }

    private suspend fun validAccessToken(): String =
        tokenMutex.withLock { accessToken?.takeIf { now() < accessExpiresAt } } ?: refreshAccessToken(rejected = null)

    private suspend fun refreshAccessToken(rejected: String?): String = tokenMutex.withLock {
        // Another call may already have refreshed while this one waited.
        accessToken?.takeIf { it != rejected && now() < accessExpiresAt }?.let { return@withLock it }
        val saved = runCatching { credentials.data.first() }.getOrNull() ?: throw SignInNeeded()
        // At most one retry with the same token: Cosmos accepts the token its latest refresh retired for
        // 60 seconds, so a lost reply can be retried once; a second retry would look like token reuse.
        val session = try { retrying(networkAttempts = REFRESH_ATTEMPTS) { api.refresh(saved.refreshToken) } }
        catch (_: CosmosApiException.Unauthorized) { expireSessionLocked(); throw SignInNeeded() }
        catch (error: CosmosApiException.Forbidden) {
            val end = endsSession(error.code) ?: throw error
            expireSessionLocked(end); throw SignInNeeded()
        }
        // Refresh tokens rotate: keep the new one before using the session, or the next start fails.
        try { credentials.save(saved.copy(refreshToken = session.refreshToken, userName = session.user.name, userId = session.user.id,
            refreshTokenExpiresAt = session.refreshTokenExpiresAt, role = session.user.role ?: saved.role, mustChangePassword = session.mustChangePassword)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw StorageProblem() }
        remember(session)
        mutableState.update { it.copy(account = CosmosAccount(session.user.name, session.user.role ?: saved.role), passwordChangeRequired = session.mustChangePassword) }
        session.accessToken
    }

    private fun remember(session: CosmosSession) {
        accessToken = session.accessToken
        accessExpiresAt = now() + session.accessTokenExpiresIn.coerceAtLeast(0) * 1_000 - EXPIRY_MARGIN_MILLIS
    }

    private suspend fun expireSession() = tokenMutex.withLock { expireSessionLocked() }

    private suspend fun expireSessionLocked(reason: CosmosSessionEnd = CosmosSessionEnd.Expired) {
        accessToken = null
        accessExpiresAt = 0
        runCatching { credentials.clear() }
        mutableState.update { it.copy(account = null, signInExpired = true, passwordChangeRequired = false, sessionEnd = reason.takeIf { it != CosmosSessionEnd.Expired }) }
        log(when (reason) {
            CosmosSessionEnd.Expired -> "Cosmos session ended; sign in again in Settings"
            CosmosSessionEnd.MachineRevoked -> "Cosmos session ended: this computer is no longer approved"
            CosmosSessionEnd.AccountDisabled -> "Cosmos session ended: the account is disabled"
        })
    }

    /** Retries network and server failures with back-off, and waits as long as Cosmos asks after 429. */
    private suspend fun <T> retrying(networkAttempts: Int = ATTEMPTS, block: suspend () -> T): T {
        var failed = 0
        var limited = 0
        while (true) {
            try { return block() }
            catch (error: CosmosApiException.Offline) { if (++failed >= networkAttempts) throw error; sleep(BACKOFF_MILLIS shl (failed - 1)) }
            catch (error: CosmosApiException.ServerTrouble) { if (++failed >= networkAttempts) throw error; sleep(BACKOFF_MILLIS shl (failed - 1)) }
            catch (error: CosmosApiException.RateLimited) {
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
    private class PasswordChangeNeeded : Exception()
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
        val RETRYABLE = setOf(CosmosSyncOutcome.Offline, CosmosSyncOutcome.ServerTrouble, CosmosSyncOutcome.RateLimited)
    }
}
