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

/** Routes each bank to its routine's database; learned answers stay in that same database file. */
class SqliteCourseAnswerBanks(
    private val databaseFolder: File,
    private val bundledDatabase: (String) -> java.io.InputStream?,
    private val legacyFolder: File,
    private val chooseFile: suspend () -> File? = { null },
    private val showFolder: suspend (File) -> Unit = {},
) : CourseAnswerBankStore {
    private val migration = Mutex()
    private var migrated = false
    private val stores = mutableMapOf<String, SqliteQuestionAnswerStore>()
    override val available = true
    override val folderPath get() = databaseFolder.absolutePath

    override fun forName(name: String): CourseAnswerBankStore = object : CourseAnswerBankStore {
        override suspend fun load() = loadName(name)
        override suspend fun save(module: String, answer: CourseAnswer) = saveName(name, module, listOf(ReviewedAnswer(answer, null)), false)
        override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>) = saveName(name, module, answers, true)
    }
    override suspend fun load() = loadName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE)
    override suspend fun save(module: String, answer: CourseAnswer) = forName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE).save(module, answer)
    override suspend fun saveReviewed(module: String, answers: List<ReviewedAnswer>) = forName(com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE).saveReviewed(module, answers)
    override suspend fun names(): List<String> { prepare(); return ROUTINE_DATABASES.flatMap { store(it).bankNames() }.distinct().sorted() }
    override suspend fun validate(name: String): String = if (loadName(name) == null) "That Q&A bank is not present in the ${routineDatabase(name)} database." else "$name is valid."
    override suspend fun openFolder(): String? { prepare(); showFolder(databaseFolder); return null }

    override suspend fun importFile(name: String): String? {
        val source = chooseFile() ?: return null
        val bank = runCatching { CourseAnswerBank.decode(source.readText()) }.getOrNull() ?: return "The selected file is not a valid answer bank."
        prepare()
        val target = store(routineDatabase(name))
        if (target.bankNames().contains(name)) return "That name already exists. Choose another name; the existing bank was not changed."
        val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(bank.encode())
        for ((module, questions) in modules) {
            val values = questions.mapNotNull { question -> question.answer?.let { PersistedLearnedAnswer(module, question.question, question.options, it) } }
            if (values.isNotEmpty() && target.save(name, values, reviewed = false) != PersistedWriteResult.Saved) return "The selected bank could not be imported without changing existing data."
        }
        return "Imported $name into ${routineDatabase(name)}."
    }

    private fun store(name: String) = stores.getOrPut(name) {
        SqliteQuestionAnswerStore(File(databaseFolder, name)) { bundledDatabase(name) }
    }
    private fun routineDatabase(bank: String) = when {
        bank.startsWith("olp-apk-") || bank == "oceantg-ver-1.0.json" -> "olp_qa.db"
        bank == "ces_qa.db" -> "seagull_qa.db"
        else -> SMY_DATABASE
    }
    private suspend fun loadName(name: String): CourseAnswerBank? {
        prepare()
        val entries = store(routineDatabase(name)).entries(name) ?: return null
        if (entries.isEmpty()) return null
        return CourseAnswerBank.decode(Json.encodeToString(entries.groupBy { it.module }.mapValues { (_, questions) ->
            questions.map { entry -> CourseAnswer(entry.question, entry.options, entry.answers.singleOrNull()) }
        }))
    }
    private suspend fun saveName(name: String, module: String, answers: List<ReviewedAnswer>, reviewed: Boolean): AnswerBankSaveResult {
        prepare()
        val values = answers.mapNotNull { review -> review.answer.answer?.let { PersistedLearnedAnswer(module, review.answer.question, review.answer.options, it, review.previousAnswer) } }
        return when (store(routineDatabase(name)).save(name, values, reviewed)) {
            PersistedWriteResult.Saved -> loadName(name)?.let(AnswerBankSaveResult::Saved) ?: AnswerBankSaveResult.Unavailable
            PersistedWriteResult.Conflict -> AnswerBankSaveResult.Conflict
            PersistedWriteResult.Invalid -> AnswerBankSaveResult.Invalid
            PersistedWriteResult.Unavailable -> AnswerBankSaveResult.Unavailable
        }
    }
    private suspend fun prepare() = migration.withLock {
        if (migrated) return@withLock
        migrateRenamedSagarDatabase()
        legacyFolder.listFiles().orEmpty().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
            val bank = runCatching { CourseAnswerBank.decode(file.readText()) }.getOrNull() ?: return@forEach
            val target = store(routineDatabase(file.name))
            Json.decodeFromString<Map<String, List<CourseAnswer>>>(bank.encode()).forEach { (module, questions) ->
                val existing = target.entries(file.name).orEmpty().associateBy { entry ->
                    CourseAnswerBank.normalize(entry.question) to entry.options.map(CourseAnswerBank::optionText).sorted()
                }
                questions.filter { it.answer != null }.chunked(100).forEach { chunk ->
                    target.save(file.name, chunk.map { question ->
                        val key = CourseAnswerBank.normalize(question.question) to question.options.map(CourseAnswerBank::optionText).sorted()
                        PersistedLearnedAnswer(module, question.question, question.options, checkNotNull(question.answer), existing[key]?.answers?.singleOrNull())
                    }, reviewed = true)
                }
            }
        }
        migrated = true
    }
    private fun migrateRenamedSagarDatabase() {
        val oldFile = File(databaseFolder, "sagarmeinyog_qa.db")
        val newFile = File(databaseFolder, SMY_DATABASE)
        if (oldFile.isFile && !newFile.exists()) oldFile.renameTo(newFile)
    }

    private companion object {
        const val SMY_DATABASE = "smy_qa.db"
        val ROUTINE_DATABASES = listOf(SMY_DATABASE, "olp_qa.db", "seagull_qa.db")
    }
}
