package com.jagdeep.persistence.qa

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Desktop-only access to AutoPilot's standalone, user-writable Q&A database. */
data class StoredQuestionAnswer(
    val module: String,
    val question: String,
    val options: List<String>,
    val answers: List<String>,
    val source: String,
)

data class LearnedQuestionAnswer(
    val module: String,
    val question: String,
    val options: List<String>,
    val answer: String,
    val previousAnswer: String? = null,
)

enum class QuestionAnswerWriteResult { Saved, Conflict, Invalid, Unavailable }

/**
 * Copies the packaged database once, then keeps learned answers in that private copy. The bundled
 * file is never altered. All SQL uses bound values; browser page text is never interpolated into SQL.
 */
class SqliteQuestionAnswerStore(
    private val databaseFile: File,
    private val bundledDatabase: () -> java.io.InputStream?,
) {
    private val mutex = Mutex()
    private val driver = BundledSQLiteDriver()

    suspend fun bankNames(): List<String> = read { db ->
        db.prepare("SELECT DISTINCT bank_name FROM qa_question_sources ORDER BY bank_name").use { row ->
            buildList { while (row.step()) add(row.getText(0)) }
        }
    }.orEmpty()

    suspend fun entries(bankName: String): List<StoredQuestionAnswer>? = read { db ->
        db.prepare("SELECT q.id,s.id,s.module,q.question_text,s.source FROM qa_question_sources s JOIN qa_questions q ON q.id=s.question_id WHERE s.bank_name=? ORDER BY s.id").use { rows ->
            rows.bindText(1, bankName)
            buildList {
                while (rows.step()) {
                    val id = rows.getLong(0)
                    val sourceId = rows.getLong(1)
                    val options = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { options ->
                        options.bindLong(1, id)
                        buildList { while (options.step()) add(options.getText(0)) }
                    }
                    val answers = db.prepare("SELECT answer_text FROM qa_source_answers WHERE source_id=? ORDER BY answer_normalized").use { answers ->
                        answers.bindLong(1, sourceId)
                        buildList { while (answers.step()) add(answers.getText(0)) }
                    }
                    add(StoredQuestionAnswer(rows.getText(2), rows.getText(3), options, answers, rows.getText(4)))
                }
            }
        }
    }

    suspend fun save(bankName: String, values: List<LearnedQuestionAnswer>, reviewed: Boolean): QuestionAnswerWriteResult {
        if (values.isEmpty() || values.any { !valid(it) }) return QuestionAnswerWriteResult.Invalid
        if (!prepare()) return QuestionAnswerWriteResult.Unavailable
        return mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                driver.open(databaseFile.absolutePath).use { db ->
                    db.prepare("BEGIN IMMEDIATE").use { it.step() }
                    try {
                        values.forEach { saveOne(db, bankName, it, reviewed) }
                        db.prepare("COMMIT").use { it.step() }
                    } catch (error: WriteConflict) {
                        db.prepare("ROLLBACK").use { it.step() }
                        return@withContext QuestionAnswerWriteResult.Conflict
                    } catch (error: Throwable) {
                        db.prepare("ROLLBACK").use { it.step() }
                        throw error
                    }
                }
                QuestionAnswerWriteResult.Saved
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                QuestionAnswerWriteResult.Unavailable
            }
        }
        }
    }

    private fun saveOne(db: androidx.sqlite.SQLiteConnection, bank: String, value: LearnedQuestionAnswer, reviewed: Boolean) {
        val questionKey = normalize(value.question)
        val optionKeys = value.options.map(::normalize).sorted()
        val candidates = db.prepare("SELECT id FROM qa_questions WHERE question_normalized=?").use { rows ->
            rows.bindText(1, questionKey)
            buildList { while (rows.step()) add(rows.getLong(0)) }
        }
        val existingId = candidates.singleOrNull { id -> optionsFor(db, id).map(::normalize).sorted() == optionKeys }
        val answerKey = normalize(value.answer)
        val now = System.currentTimeMillis()
        val id = existingId ?: run {
            db.prepare("INSERT INTO qa_questions(question_text,question_normalized) VALUES (?,?)").use { row ->
                row.bindText(1, value.question); row.bindText(2, questionKey); row.step()
            }
            val inserted = db.prepare("SELECT last_insert_rowid()").use { row -> row.step(); row.getLong(0) }
            value.options.forEachIndexed { index, option ->
                db.prepare("INSERT INTO qa_options(question_id,position,option_text,option_normalized) VALUES (?,?,?,?)").use { row ->
                    row.bindLong(1, inserted); row.bindLong(2, (index + 1).toLong()); row.bindText(3, option); row.bindText(4, normalize(option)); row.step()
                }
            }
            inserted
        }
        val sourceId = db.prepare("SELECT id FROM qa_question_sources WHERE question_id=? AND bank_name=? AND module=? ORDER BY id LIMIT 1").use { row ->
            row.bindLong(1, id); row.bindText(2, bank); row.bindText(3, value.module)
            if (row.step()) row.getLong(0) else {
                db.prepare("INSERT INTO qa_question_sources(question_id,routine,bank_name,module,source,source_reference,imported_at) VALUES (?,'learned',?,?, 'learned',NULL,CURRENT_TIMESTAMP)").use { insert ->
                    insert.bindLong(1, id); insert.bindText(2, bank); insert.bindText(3, value.module); insert.step()
                }
                db.prepare("SELECT last_insert_rowid()").use { inserted -> inserted.step(); inserted.getLong(0) }
            }
        }
        val existing = answersFor(db, sourceId).map(::normalize).toSet()
        val previous = value.previousAnswer?.let(::normalize)
        if (reviewed && ((previous != null && existing != setOf(previous)) || (previous == null && existing.isNotEmpty() && existing != setOf(answerKey)))) throw WriteConflict()
        if (!reviewed && existing.isNotEmpty() && existing != setOf(answerKey)) throw WriteConflict()
        db.prepare("DELETE FROM qa_source_answers WHERE source_id=?").use { row -> row.bindLong(1, sourceId); row.step() }
        db.prepare("INSERT INTO qa_source_answers(source_id,answer_text,answer_normalized,learned_at,reviewed_at) VALUES (?,?,?,?,?)").use { row ->
            row.bindLong(1, sourceId); row.bindText(2, value.answer); row.bindText(3, answerKey); row.bindLong(4, now); if (reviewed) row.bindLong(5, now) else row.bindNull(5); row.step()
        }
    }

    private fun optionsFor(db: androidx.sqlite.SQLiteConnection, id: Long): List<String> = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { row ->
        row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) }
    }
    private fun answersFor(db: androidx.sqlite.SQLiteConnection, id: Long): List<String> = db.prepare("SELECT answer_text FROM qa_source_answers WHERE source_id=?").use { row ->
        row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) }
    }
    private suspend fun <T> read(block: (androidx.sqlite.SQLiteConnection) -> T): T? = withContext(Dispatchers.IO) {
        if (!prepare()) return@withContext null
        runCatching { driver.open(databaseFile.absolutePath).use(block) }.getOrNull()
    }
    private suspend fun prepare(): Boolean = mutex.withLock {
        if (databaseFile.isFile) return@withLock true
        val input = bundledDatabase() ?: return@withLock false
        runCatching {
            databaseFile.parentFile.mkdirs()
            val temporary = File(databaseFile.parentFile, ".${databaseFile.name}.tmp")
            input.use { source -> temporary.outputStream().use(source::copyTo) }
            Files.move(temporary.toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
    }
    private fun valid(value: LearnedQuestionAnswer) = value.module.isNotBlank() && value.question.isNotBlank() && value.options.size in 2..20 &&
        value.options.all { it.isNotBlank() } && value.options.map(::normalize).toSet().size == value.options.size && normalize(value.answer) in value.options.map(::normalize)
    private fun normalize(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().lowercase()
    private class WriteConflict : RuntimeException()
}
