package com.jagdeep.home.data.cosmos

import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.domain.cosmos.CosmosAnswer
import com.jagdeep.home.domain.cosmos.CosmosEntry
import com.jagdeep.home.domain.cosmos.CosmosOption
import com.jagdeep.home.domain.cosmos.CosmosQaStore
import com.jagdeep.home.domain.cosmos.CosmosSubmission
import com.jagdeep.persistence.qa.KeyedAnswer
import com.jagdeep.persistence.qa.PendingQuestionAnswer
import com.jagdeep.persistence.qa.RemoteQuestionAnswer

/**
 * Maps each Cosmos bank to its routine Q&A file. Entries are stored under their own `bankName`,
 * `module` and `questionKey` (the file's identity), so routines use them exactly like bundled
 * answers: exact matches only.
 */
class SqliteCosmosQaStore(
    private val files: SqliteCourseAnswerBanks,
    private val now: () -> Long = System::currentTimeMillis,
) : CosmosQaStore {
    override val banks: List<String> = BANK_FILES.keys.toList()

    override suspend fun lastRevision(bank: String): Long? {
        val store = files.routineStore(file(bank))
        return store.syncValue(REVISION_KEY)?.toLongOrNull() ?: if (store.pendingUploadCount() == null) null else 0L
    }

    override suspend fun apply(bank: String, entries: List<CosmosEntry>, nextAfter: Long): Boolean {
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
                authoritative = entry.authoritative,
            )
        }
        return files.routineStore(file(bank)).applyRemote(values, REVISION_KEY, nextAfter) != null
    }

    override suspend fun pending(bank: String, limit: Int): List<CosmosSubmission>? =
        files.routineStore(file(bank)).pendingUploads(limit)?.map(::submission)

    override suspend fun markSubmitted(bank: String, statuses: Map<String, String>): Boolean =
        statuses.isEmpty() || files.routineStore(file(bank)).markUploads(statuses, now())

    override suspend fun pendingCount(): Int = BANK_FILES.values.sumOf { files.routineStore(it).pendingUploadCount() ?: 0 }

    private fun file(bank: String) = requireNotNull(BANK_FILES[bank]) { "Unknown Cosmos bank" }

    companion object {
        /** Cosmos bank key → routine file (the contract's table). */
        private val BANK_FILES = linkedMapOf("smy" to "smy.db", "olp" to "olp.db", "seagull" to "seagull.db", "marinesamraj" to "marinesamraj.db")
        private const val REVISION_KEY = "cosmos_revision"

        /** Shapes a local answer as the contract's submission, with the file's own keys. */
        internal fun submission(value: PendingQuestionAnswer) = CosmosSubmission(
            clientId = value.clientId,
            bankName = value.bankName,
            module = value.module,
            question = value.question,
            questionKey = value.questionKey,
            options = value.options.mapIndexed { index, option -> CosmosOption(index + 1, option) },
            answer = CosmosAnswer(value.answer, value.answerKey),
            learnedAt = (value.learnedAt ?: value.reviewedAt)?.let { java.time.Instant.ofEpochMilli(it).toString() },
            reviewedAt = value.reviewedAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }
}
