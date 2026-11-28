package com.jagdeep.home.domain.cosmos

import kotlinx.serialization.Serializable

/**
 * The Cosmos API (private/docs/shared/01_QA_SYNC_API.md, 02_DIAGNOSTICS_AND_DOWNLOADS_API.md and
 * 03_ACCOUNTS_AND_MACHINES_API.md). Only Q&A banks, the account's own session, this computer's hashed
 * machine ID, sanitised routine run logs and the admin's account administration travel through it.
 */
interface CosmosApi {
    /**
     * [machineId] is the hashed [MachineIdentity.machineId], never the raw identifier. [totpCode] (the admin's
     * authenticator or recovery code) is sent only when given.
     */
    suspend fun login(email: String, password: String, deviceName: String, machineId: String, platform: String, totpCode: String? = null): CosmosSession
    suspend fun refresh(refreshToken: String): CosmosSession
    suspend fun logout(refreshToken: String)
    suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CosmosEntriesPage
    suspend fun submit(accessToken: String, bank: String, submissions: List<CosmosSubmission>): List<CosmosSubmissionResult>

    /** `POST /api/diagnostics/runs` with [body] (a [RunLogUploadBody]) gzipped; returns `stored` or `duplicate`. */
    suspend fun uploadRun(accessToken: String, body: String): String

    /** `POST /api/auth/app/change-password`; a session in the reply (if Cosmos sends one) replaces the current one. */
    suspend fun changePassword(accessToken: String, currentPassword: String, newPassword: String): CosmosSession? = throw CosmosApiException.NotFound()

    /** `POST /api/auth/app/browser-link`: a one-time link for the default browser. */
    suspend fun browserLink(accessToken: String): CosmosBrowserLink = throw CosmosApiException.NotFound()

    suspend fun adminAccounts(accessToken: String, status: String?, query: String?, page: Int, pageSize: Int): CosmosAccountsPage = throw CosmosApiException.NotFound()
    suspend fun adminCreateAccount(accessToken: String, request: CosmosCreateAccountRequest): CosmosCreatedAccountResponse = throw CosmosApiException.NotFound()
    suspend fun adminUpdateAccount(accessToken: String, id: String, request: CosmosUpdateAccountRequest): CosmosAccountWire = throw CosmosApiException.NotFound()
    suspend fun adminResetPassword(accessToken: String, id: String): String = throw CosmosApiException.NotFound()
    suspend fun adminMachines(accessToken: String, status: String?, page: Int, pageSize: Int): CosmosMachinesPage = throw CosmosApiException.NotFound()
    suspend fun adminApproveMachine(accessToken: String, id: String): CosmosMachineWire = throw CosmosApiException.NotFound()
    suspend fun adminRevokeMachine(accessToken: String, id: String): CosmosMachineWire = throw CosmosApiException.NotFound()
}

/** Failures carry no response body, token or request detail, so they are safe to log or show. */
sealed class CosmosApiException(message: String) : Exception(message) {
    /** `401`, with the contract's [code] (for example `TOTP_REQUIRED`) when Cosmos sent one. */
    class Unauthorized(val code: String? = null) : CosmosApiException("Cosmos refused the session${code?.let { " ($it)" } ?: ""}")
    class RateLimited(val retryAfterSeconds: Long?) : CosmosApiException("Cosmos asked to wait")
    class ServerTrouble(val status: Int) : CosmosApiException("Cosmos server error $status")
    class Offline : CosmosApiException("Cosmos could not be reached")
    class Rejected(val status: Int) : CosmosApiException("Cosmos rejected the request ($status)")
    class NotFound : CosmosApiException("Cosmos does not know this bank")
    /** `403`, with the contract's [code] (for example `MACHINE_PENDING`) when Cosmos sent one. */
    class Forbidden(val code: String?) : CosmosApiException("Cosmos refused the request (${code ?: "403"})")
    /** `409`: for example approving a machine beyond the account's limit. */
    class Conflict(val code: String?) : CosmosApiException("Cosmos reported a conflict (${code ?: "409"})")

    companion object {
        const val MACHINE_PENDING = "MACHINE_PENDING"
        const val MACHINE_REVOKED = "MACHINE_REVOKED"
        const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
        const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"
        const val TOTP_REQUIRED = "TOTP_REQUIRED"
        const val TOTP_INVALID = "TOTP_INVALID"
        const val TOTP_SETUP_REQUIRED = "TOTP_SETUP_REQUIRED"
    }
}

@Serializable data class CosmosUser(val id: String, val name: String, val role: String? = null)

@Serializable data class CosmosMachineRef(val id: String, val status: String? = null)

@Serializable data class CosmosSession(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: String? = null,
    val user: CosmosUser,
    val machine: CosmosMachineRef? = null,
    /** The account still has its temporary password: only change-password and logout work. */
    val mustChangePassword: Boolean = false,
) {
    /** Never prints tokens. */
    override fun toString() = "CosmosSession(user=${user.name})"
}

@Serializable data class CosmosOption(val position: Int, val text: String)

/** [answerKey] is the routine file's own `qa_answers.answer_key`, opaque to Cosmos. */
@Serializable data class CosmosAnswer(val text: String, val answerKey: String)

/** Identity: (bank, bankName, module, questionKey), the unique key of the routine file's `qa_questions`. */
@Serializable data class CosmosEntry(
    val id: String,
    val bank: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<CosmosOption>,
    val answers: List<CosmosAnswer>,
    val source: String? = null,
    val status: String,
    val revision: Long,
    val updatedAt: String? = null,
) {
    val retired get() = status == "retired"
}

@Serializable data class CosmosEntriesPage(val entries: List<CosmosEntry>, val nextAfter: Long, val hasMore: Boolean)

@Serializable data class CosmosSubmission(
    val clientId: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<CosmosOption>,
    val answer: CosmosAnswer,
    val learnedAt: String? = null,
    val reviewedAt: String? = null,
) {
    /** The contract's size limits; an answer outside them can never be sent. */
    fun fitsContract(): Boolean = bankName.isNotBlank() && bankName.length <= 200 &&
        module.isNotBlank() && module.length <= 200 &&
        question.isNotBlank() && question.length <= 4_000 &&
        options.size in 2..12 && options.all { it.text.isNotBlank() && it.text.length <= 1_000 } &&
        answer.text.isNotBlank() && answer.text.length <= 1_000

    /**
     * The contract's key format: `questionKey` is 64 lowercase hex, `answerKey` is the file's normalised
     * answer text (1–1,000 characters). Kept apart from [fitsContract]: an answer whose keys don't match
     * waits on this computer instead of being given up.
     */
    fun keysFitContract(): Boolean = HEX_KEY.matches(questionKey) && answer.answerKey.length in 1..1_000

    private companion object { val HEX_KEY = Regex("[0-9a-f]{64}") }
}

@Serializable data class CosmosSubmissionResult(val clientId: String, val status: String) {
    companion object {
        /** Every status Cosmos returns means the submission is stored: mark it sent. */
        val SENT = setOf("pending", "duplicate", "already_known", "conflict")
    }
}

/** What secure storage keeps between app starts. The access token is never stored. */
@Serializable data class CosmosCredentials(
    val refreshToken: String,
    val userName: String,
    val userId: String,
    val refreshTokenExpiresAt: String? = null,
    val role: String? = null,
    val mustChangePassword: Boolean = false,
) {
    override fun toString() = "CosmosCredentials(user=$userName)"
}

/** The local routine Q&A files, one per Cosmos bank. */
interface CosmosQaStore {
    val banks: List<String>
    /** The highest revision applied for [bank], or null when its file is unavailable. */
    suspend fun lastRevision(bank: String): Long?
    /** Applies one page and advances the bank's cursor to [nextAfter] in the same transaction. */
    suspend fun apply(bank: String, entries: List<CosmosEntry>, nextAfter: Long): Boolean
    /** Confirmed answers not yet sent, oldest first; null when the file is unavailable. */
    suspend fun pending(bank: String, limit: Int): List<CosmosSubmission>?
    /** Maps client id to its outcome (a Cosmos status, or a local reason it can't be sent). */
    suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean
    suspend fun pendingCount(): Int
}

/** The browser-link reply; [url] carries a one-time secret in its fragment and is never logged. */
@Serializable data class CosmosBrowserLink(val url: String, val expiresIn: Long? = null) {
    override fun toString() = "CosmosBrowserLink(expiresIn=$expiresIn)"
}

@Serializable data class CosmosAccountWire(
    val id: String,
    val name: String,
    val email: String,
    val role: String? = null,
    val status: String? = null,
    val machineLimit: Int = 1,
    val approvedMachines: Int = 0,
    val pendingMachines: Int = 0,
    val mustChangePassword: Boolean = false,
    val createdAt: String? = null,
    val lastSignInAt: String? = null,
)

@Serializable data class CosmosAccountsPage(val accounts: List<CosmosAccountWire>, val page: Int = 1, val pageSize: Int = 0, val total: Int = 0)

@Serializable data class CosmosCreateAccountRequest(val name: String, val email: String, val machineLimit: Int)

/** [temporaryPassword] is shown once to the admin; [toString] never prints it. */
@Serializable data class CosmosCreatedAccountResponse(val account: CosmosAccountWire, val temporaryPassword: String) {
    override fun toString() = "CosmosCreatedAccountResponse(account=${account.id})"
}

/** Only the fields that change are sent. */
@Serializable data class CosmosUpdateAccountRequest(val name: String? = null, val machineLimit: Int? = null, val status: String? = null)

@Serializable data class CosmosMachineAccount(val id: String, val name: String? = null, val email: String? = null)

/** The machine record. `machineId` (the hash) is read past on purpose: the app never shows or keeps another machine's ID. */
@Serializable data class CosmosMachineWire(
    val id: String,
    val label: String = "",
    val platform: String? = null,
    val status: String? = null,
    val firstSeenAt: String? = null,
    val lastSeenAt: String? = null,
    val approvedAt: String? = null,
    val account: CosmosMachineAccount? = null,
)

@Serializable data class CosmosMachinesPage(val machines: List<CosmosMachineWire>, val page: Int = 1, val pageSize: Int = 0, val total: Int = 0)
