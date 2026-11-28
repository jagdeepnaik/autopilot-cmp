package com.jagdeep.home.domain.compass

import kotlinx.serialization.Serializable

/**
 * The Compass Q&A sync API (private/docs/shared/01_QA_SYNC_API.md). Only Q&A banks and the
 * account's own session travel through it.
 */
interface CompassApi {
    suspend fun login(email: String, password: String, deviceName: String): CompassSession
    suspend fun refresh(refreshToken: String): CompassSession
    suspend fun logout(refreshToken: String)
    suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CompassEntriesPage
    suspend fun submit(accessToken: String, bank: String, submissions: List<CompassSubmission>): List<CompassSubmissionResult>
}

/** Failures carry no response body, token or request detail, so they are safe to log or show. */
sealed class CompassApiException(message: String) : Exception(message) {
    class Unauthorized : CompassApiException("Compass refused the session")
    class RateLimited(val retryAfterSeconds: Long?) : CompassApiException("Compass asked to wait")
    class ServerTrouble(val status: Int) : CompassApiException("Compass server error $status")
    class Offline : CompassApiException("Compass could not be reached")
    class Rejected(val status: Int) : CompassApiException("Compass rejected the request ($status)")
    class NotFound : CompassApiException("Compass does not know this bank")
}

@Serializable data class CompassUser(val id: String, val name: String, val role: String? = null)

@Serializable data class CompassSession(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: String? = null,
    val user: CompassUser,
) {
    /** Never prints tokens. */
    override fun toString() = "CompassSession(user=${user.name})"
}

@Serializable data class CompassOption(val position: Int, val text: String)

/** [answerKey] is the routine file's own `qa_answers.answer_key`, opaque to Compass. */
@Serializable data class CompassAnswer(val text: String, val answerKey: String)

/** Identity: (bank, bankName, module, questionKey), the unique key of the routine file's `qa_questions`. */
@Serializable data class CompassEntry(
    val id: String,
    val bank: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<CompassOption>,
    val answers: List<CompassAnswer>,
    val source: String? = null,
    val status: String,
    val revision: Long,
    val updatedAt: String? = null,
) {
    val retired get() = status == "retired"
}

@Serializable data class CompassEntriesPage(val entries: List<CompassEntry>, val nextAfter: Long, val hasMore: Boolean)

@Serializable data class CompassSubmission(
    val clientId: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<CompassOption>,
    val answer: CompassAnswer,
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

@Serializable data class CompassSubmissionResult(val clientId: String, val status: String) {
    companion object {
        /** Every status Compass returns means the submission is stored: mark it sent. */
        val SENT = setOf("pending", "duplicate", "already_known", "conflict")
    }
}

/** What secure storage keeps between app starts. The access token is never stored. */
@Serializable data class CompassCredentials(
    val refreshToken: String,
    val userName: String,
    val userId: String,
    val refreshTokenExpiresAt: String? = null,
) {
    override fun toString() = "CompassCredentials(user=$userName)"
}

/** The local routine Q&A files, one per Compass bank. */
interface CompassQaStore {
    val banks: List<String>
    /** The highest revision applied for [bank], or null when its file is unavailable. */
    suspend fun lastRevision(bank: String): Long?
    /** Applies one page and advances the bank's cursor to [nextAfter] in the same transaction. */
    suspend fun apply(bank: String, entries: List<CompassEntry>, nextAfter: Long): Boolean
    /** Confirmed answers not yet sent, oldest first; null when the file is unavailable. */
    suspend fun pending(bank: String, limit: Int): List<CompassSubmission>?
    /** Maps client id to its outcome (a Compass status, or a local reason it can't be sent). */
    suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean
    suspend fun pendingCount(): Int
}
