package com.jagdeep.home.data.compass

import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.domain.compass.CompassAnswer
import com.jagdeep.home.domain.compass.CompassEntry
import com.jagdeep.home.domain.compass.CompassOption
import com.jagdeep.home.domain.compass.CompassQaStore
import com.jagdeep.home.domain.compass.CompassSubmission
import com.jagdeep.persistence.qa.KeyedAnswer
import com.jagdeep.persistence.qa.PendingQuestionAnswer
import com.jagdeep.persistence.qa.RemoteQuestionAnswer

/**
 * Maps each Compass bank to its routine Q&A file. Entries are stored under their own `bankName`,
 * `module` and `questionKey` (the file's identity), so routines use them exactly like bundled
 * answers: exact matches only.
 */
class SqliteCompassQaStore(
    private val files: SqliteCourseAnswerBanks,
    private val now: () -> Long = System::currentTimeMillis,
) : CompassQaStore {
    override val banks: List<String> = BANK_FILES.keys.toList()

    override suspend fun lastRevision(bank: String): Long? {
        val store = files.routineStore(file(bank))
        return store.syncValue(REVISION_KEY)?.toLongOrNull() ?: if (store.pendingUploadCount() == null) null else 0L
    }

    override suspend fun apply(bank: String, entries: List<CompassEntry>, nextAfter: Long): Boolean {
        val values = entries.map { entry ->
            RemoteQuestionAnswer(
                entryId = entry.id,
                bankName = entry.bankName,
                module = entry.module,
                question = entry.question,
                questionKey = entry.questionKey,
                options = entry.options.sortedBy { it.position }.map { it.text },
                answers = entry.answers.map { KeyedAnswer(it.text, it.answerKey) },
                revision = entry.revision,
                retired = entry.retired,
            )
        }
        return files.routineStore(file(bank)).applyRemote(values, REVISION_KEY, nextAfter) != null
    }

    override suspend fun pending(bank: String, limit: Int): List<CompassSubmission>? =
        files.routineStore(file(bank)).pendingUploads(limit)?.map(::submission)

    override suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean =
        statuses.isEmpty() || files.routineStore(file(bank)).markUploads(statuses, now())

    override suspend fun pendingCount(): Int = BANK_FILES.values.sumOf { files.routineStore(it).pendingUploadCount() ?: 0 }

    private fun file(bank: String) = requireNotNull(BANK_FILES[bank]) { "Unknown Compass bank" }

    companion object {
        /** Compass bank key → routine file (the contract's table). */
        private val BANK_FILES = linkedMapOf("smy" to "smy.db", "olp" to "olp.db", "seagull" to "seagull.db", "marinesamraj" to "marinesamraj.db")
        private const val REVISION_KEY = "compass_revision"

        /** Shapes a local answer as the contract's submission, with the file's own keys. */
        internal fun submission(value: PendingQuestionAnswer) = CompassSubmission(
            clientId = value.clientId,
            bankName = value.bankName,
            module = value.module,
            question = value.question,
            questionKey = value.questionKey,
            options = value.options.mapIndexed { index, option -> CompassOption(index + 1, option) },
            answer = CompassAnswer(value.answer, value.answerKey),
            learnedAt = (value.learnedAt ?: value.reviewedAt)?.let { java.time.Instant.ofEpochMilli(it).toString() },
            reviewedAt = value.reviewedAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }
}
