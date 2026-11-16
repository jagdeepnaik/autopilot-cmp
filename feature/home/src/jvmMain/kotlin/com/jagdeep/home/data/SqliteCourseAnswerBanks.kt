package com.jagdeep.home.data

import com.jagdeep.home.domain.AnswerBankSaveResult
import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.CourseAnswerBank
import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.ReviewedAnswer
import com.jagdeep.persistence.qa.LearnedQuestionAnswer as PersistedLearnedAnswer
import com.jagdeep.persistence.qa.QuestionAnswerWriteResult as PersistedWriteResult
import com.jagdeep.persistence.qa.SqliteQuestionAnswerStore
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Bridges the routine's established answer-bank contract onto the single persistent SQLite bank. */
class SqliteCourseAnswerBanks(
    private val databaseFile: File,
    bundledDatabase: () -> java.io.InputStream?,
    private val legacyFolder: File,
    private val chooseFile: suspend () -> File? = { null },
    private val showFolder: suspend (File) -> Unit = {},
) : CourseAnswerBankStore {
    private val store = SqliteQuestionAnswerStore(databaseFile, bundledDatabase)
    private val migration = Mutex()
    private var migrated = false
    override val available = true
    override val folderPath get() = databaseFile.absolutePath

    override fun forName(name: String): CourseAnswerBankStore = object : CourseAnswerBankStore {
        override suspend fun load() = loadName(name)
        override suspend fun save(module: String, answer: CourseAnswer) = saveName(name, module, listOf(ReviewedAnswer(answer, null)), false)
        override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>) = saveName(name, module, answers, true)
    }

    override suspend fun load() = loadName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE)
    override suspend fun save(module: String, answer: CourseAnswer) = forName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE).save(module, answer)
    override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>) = forName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE).saveReviewed(module, answers)
    override suspend fun names(): List<String> { prepare(); return store.bankNames() }
    override suspend fun validate(name: String): String {
        val bank = loadName(name)
        return if (bank == null) "That Q&A bank is not present in autopilot_qa.db." else "$name is valid in autopilot_qa.db."
    }
    override suspend fun importFile(name: String): String? {
        val source = chooseFile() ?: return null
        val bank = runCatching { CourseAnswerBank.decode(source.readText()) }.getOrNull()
            ?: return "The selected file is not a valid answer bank."
        prepare()
        if (store.bankNames().contains(name)) return "That name already exists. Choose another name; the existing bank was not changed."
        val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(bank.encode())
        for ((module, questions) in modules) {
            val values = questions.mapNotNull { question -> question.answer?.let { PersistedLearnedAnswer(module, question.question, question.options, it) } }
            if (values.isNotEmpty() && store.save(name, values, reviewed = false) != PersistedWriteResult.Saved)
                return "The selected bank could not be imported without changing existing data."
        }
        return "Imported $name into autopilot_qa.db. Choose this file in the routine options."
    }
    override suspend fun openFolder(): String? { prepare(); showFolder(databaseFile.parentFile); return null }

    private suspend fun loadName(name: String): CourseAnswerBank? {
        prepare()
        val entries = store.entries(name) ?: return null
        if (entries.isEmpty()) return null
        val modules = entries.groupBy { it.module }.mapValues { (_, questions) -> questions.map { entry ->
            CourseAnswer(entry.question, entry.options, entry.answers.singleOrNull())
        } }
        return CourseAnswerBank.decode(Json.encodeToString(modules))
    }

    private suspend fun saveName(name: String, module: String, answers: List<ReviewedAnswer>, reviewed: Boolean): AnswerBankSaveResult {
        prepare()
        val values = answers.mapNotNull { reviewedAnswer ->
            val answer = reviewedAnswer.answer.answer ?: return@mapNotNull null
            PersistedLearnedAnswer(module, reviewedAnswer.answer.question, reviewedAnswer.answer.options, answer, reviewedAnswer.previousAnswer)
        }
        return when (store.save(name, values, reviewed)) {
            PersistedWriteResult.Saved -> loadName(name)?.let(AnswerBankSaveResult::Saved) ?: AnswerBankSaveResult.Unavailable
            PersistedWriteResult.Conflict -> AnswerBankSaveResult.Conflict
            PersistedWriteResult.Invalid -> AnswerBankSaveResult.Invalid
            PersistedWriteResult.Unavailable -> AnswerBankSaveResult.Unavailable
        }
    }

    /** Existing user JSON files are copied into SQLite once, with their saved answers taking precedence. */
    private suspend fun prepare() = migration.withLock {
        if (migrated) return@withLock
        val files = legacyFolder.listFiles().orEmpty().filter { it.isFile && it.extension.equals("json", true) }
        files.forEach { file ->
            val bank = runCatching { CourseAnswerBank.decode(file.readText()) }.getOrNull() ?: return@forEach
            val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(bank.encode())
            modules.forEach { (module, questions) ->
                val existing = store.entries(file.name).orEmpty().associateBy { entry ->
                    CourseAnswerBank.normalize(entry.question) to entry.options.map(CourseAnswerBank::optionText).sorted()
                }
                questions.filter { it.answer != null }.chunked(100).forEach { chunk ->
                    val values = chunk.map { question ->
                        val key = CourseAnswerBank.normalize(question.question) to question.options.map(CourseAnswerBank::optionText).sorted()
                        PersistedLearnedAnswer(module, question.question, question.options, checkNotNull(question.answer), existing[key]?.answers?.singleOrNull())
                    }
                    store.save(file.name, values, reviewed = true)
                }
            }
        }
        migrated = true
    }
}
