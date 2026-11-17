package com.jagdeep.persistence.qa

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.security.DigestInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class StoredQuestionAnswer(val module: String, val question: String, val options: List<String>, val answers: List<String>, val source: String)
data class LearnedQuestionAnswer(val module: String, val question: String, val options: List<String>, val answer: String, val previousAnswer: String? = null)
enum class QuestionAnswerWriteResult { Saved, Conflict, Invalid, Unavailable }

/** One routine's bundled Q&A and learned answers share one private SQLite file. */
class SqliteQuestionAnswerStore(private val databaseFile: File, private val bundledDatabase: () -> java.io.InputStream?) {
    private val mutex = Mutex()
    private val driver = BundledSQLiteDriver()

    suspend fun bankNames(): List<String> = read { db ->
        db.prepare("SELECT DISTINCT bank_name FROM qa_questions ORDER BY bank_name").use { row -> buildList { while (row.step()) add(row.getText(0)) } }
    }.orEmpty()

    suspend fun entries(bankName: String): List<StoredQuestionAnswer>? = read { db ->
        db.prepare("SELECT id,module,question_text FROM qa_questions WHERE bank_name=? ORDER BY id").use { rows ->
            rows.bindText(1, bankName)
            buildList {
                while (rows.step()) {
                    val id = rows.getLong(0)
                    val source = if (db.prepare("SELECT 1 FROM qa_answers WHERE question_id=? AND source='learned' LIMIT 1").use { check -> check.bindLong(1, id); check.step() }) "learned" else "source"
                    add(StoredQuestionAnswer(rows.getText(1), rows.getText(2), optionsFor(db, id), answersFor(db, id), source))
                }
            }
        }
    }

    suspend fun save(bankName: String, values: List<LearnedQuestionAnswer>, reviewed: Boolean): QuestionAnswerWriteResult {
        if (values.isEmpty() || values.any { !valid(it) }) return QuestionAnswerWriteResult.Invalid
        if (!prepare()) return QuestionAnswerWriteResult.Unavailable
        return mutex.withLock { withContext(Dispatchers.IO) {
            runCatching {
                driver.open(databaseFile.absolutePath).use { db ->
                    db.prepare("BEGIN IMMEDIATE").use { it.step() }
                    try { values.forEach { saveOne(db, bankName, it, reviewed) }; db.prepare("COMMIT").use { it.step() } }
                    catch (error: WriteConflict) { db.prepare("ROLLBACK").use { it.step() }; return@withContext QuestionAnswerWriteResult.Conflict }
                    catch (error: Throwable) { db.prepare("ROLLBACK").use { it.step() }; throw error }
                }
                QuestionAnswerWriteResult.Saved
            }.getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error; QuestionAnswerWriteResult.Unavailable }
        } }
    }

    private fun saveOne(db: SQLiteConnection, bank: String, value: LearnedQuestionAnswer, reviewed: Boolean) {
        val key = questionKey(value.question, value.options)
        val id = db.prepare("SELECT id FROM qa_questions WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, bank); row.bindText(2, value.module); row.bindText(3, key)
            if (row.step()) row.getLong(0) else {
                db.prepare("INSERT INTO qa_questions(bank_name,module,question_text,question_key) VALUES (?,?,?,?)").use { insert ->
                    insert.bindText(1, bank); insert.bindText(2, value.module); insert.bindText(3, value.question); insert.bindText(4, key); insert.step()
                }
                val inserted = db.prepare("SELECT last_insert_rowid()").use { rowId -> rowId.step(); rowId.getLong(0) }
                value.options.forEachIndexed { index, option -> db.prepare("INSERT INTO qa_options(question_id,position,option_text) VALUES (?,?,?)").use { optionRow ->
                    optionRow.bindLong(1, inserted); optionRow.bindLong(2, (index + 1).toLong()); optionRow.bindText(3, option); optionRow.step()
                } }
                inserted
            }
        }
        val answerKey = normalize(value.answer)
        val existing = answersFor(db, id).map(::normalize).toSet()
        val previous = value.previousAnswer?.let(::normalize)
        if (reviewed && ((previous != null && existing != setOf(previous)) || (previous == null && existing.isNotEmpty() && existing != setOf(answerKey)))) throw WriteConflict()
        if (!reviewed && existing.isNotEmpty() && existing != setOf(answerKey)) throw WriteConflict()
        db.prepare("DELETE FROM qa_answers WHERE question_id=?").use { row -> row.bindLong(1, id); row.step() }
        db.prepare("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at) VALUES (?,?,?,'learned',?,?)").use { row ->
            val now = System.currentTimeMillis(); row.bindLong(1, id); row.bindText(2, value.answer); row.bindText(3, answerKey); row.bindLong(4, now); if (reviewed) row.bindLong(5, now) else row.bindNull(5); row.step()
        }
    }

    private fun optionsFor(db: SQLiteConnection, id: Long): List<String> = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { row -> row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) } }
    private fun answersFor(db: SQLiteConnection, id: Long): List<String> = db.prepare("SELECT answer_text FROM qa_answers WHERE question_id=? ORDER BY answer_key").use { row -> row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) } }
    private suspend fun <T> read(block: (SQLiteConnection) -> T): T? = withContext(Dispatchers.IO) { if (!prepare()) null else runCatching { driver.open(databaseFile.absolutePath).use(block) }.getOrNull() }
    private suspend fun prepare(): Boolean = mutex.withLock {
        val input = bundledDatabase() ?: return@withLock false
        runCatching {
            databaseFile.parentFile.mkdirs()
            val bundled = File(databaseFile.parentFile, ".${databaseFile.name}.bundled.tmp")
            val fingerprint = input.use { source ->
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(source, digest).use { checked -> bundled.outputStream().use(checked::copyTo) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!databaseFile.isFile) {
                Files.move(bundled.toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                driver.open(databaseFile.absolutePath).use { installed ->
                    installed.prepare("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)").use { it.step() }
                    val current = installed.prepare("SELECT value FROM metadata WHERE key='bundled_sha256'").use { row -> if (row.step()) row.getText(0) else null }
                    if (current != fingerprint) {
                        driver.open(bundled.absolutePath).use { source -> mergeBundledSource(installed, source) }
                        installed.prepare("INSERT OR REPLACE INTO metadata(key,value) VALUES ('bundled_sha256',?)").use { row -> row.bindText(1, fingerprint); row.step() }
                    }
                }
                bundled.delete()
            }
            true
        }.getOrElse { false }
    }

    /** Adds current bundled questions without discarding answers learned in the user's copy. */
    private fun mergeBundledSource(installed: SQLiteConnection, source: SQLiteConnection) {
        installed.prepare("BEGIN IMMEDIATE").use { it.step() }
        try {
            source.prepare("SELECT id,bank_name,module,question_text,question_key FROM qa_questions ORDER BY id").use { questions ->
                while (questions.step()) {
                    val sourceId = questions.getLong(0)
                    val bank = questions.getText(1)
                    val module = questions.getText(2)
                    val question = questions.getText(3)
                    val key = questions.getText(4)
                    val installedId = installed.prepare("SELECT id FROM qa_questions WHERE bank_name=? AND module=? AND question_key=?").use { row ->
                        row.bindText(1, bank); row.bindText(2, module); row.bindText(3, key)
                        if (row.step()) row.getLong(0) else null
                    } ?: run {
                        installed.prepare("INSERT INTO qa_questions(bank_name,module,question_text,question_key) VALUES (?,?,?,?)").use { row ->
                            row.bindText(1, bank); row.bindText(2, module); row.bindText(3, question); row.bindText(4, key); row.step()
                        }
                        val id = installed.prepare("SELECT last_insert_rowid()").use { row -> row.step(); row.getLong(0) }
                        source.prepare("SELECT position,option_text FROM qa_options WHERE question_id=? ORDER BY position").use { options ->
                            options.bindLong(1, sourceId)
                            while (options.step()) installed.prepare("INSERT INTO qa_options(question_id,position,option_text) VALUES (?,?,?)").use { row ->
                                row.bindLong(1, id); row.bindLong(2, options.getLong(0)); row.bindText(3, options.getText(1)); row.step()
                            }
                        }
                        id
                    }
                    val hasLearned = installed.prepare("SELECT 1 FROM qa_answers WHERE question_id=? AND source='learned' LIMIT 1").use { row -> row.bindLong(1, installedId); row.step() }
                    if (!hasLearned) {
                        installed.prepare("DELETE FROM qa_answers WHERE question_id=?").use { row -> row.bindLong(1, installedId); row.step() }
                        source.prepare("SELECT answer_text,answer_key FROM qa_answers WHERE question_id=? ORDER BY answer_key").use { answers ->
                            answers.bindLong(1, sourceId)
                            while (answers.step()) installed.prepare("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at) VALUES (?,?,?,'source',NULL,NULL)").use { row ->
                                row.bindLong(1, installedId); row.bindText(2, answers.getText(0)); row.bindText(3, answers.getText(1)); row.step()
                            }
                        }
                    }
                }
            }
            installed.prepare("COMMIT").use { it.step() }
        } catch (error: Throwable) {
            installed.prepare("ROLLBACK").use { it.step() }
            throw error
        }
    }
    private fun valid(value: LearnedQuestionAnswer) = value.module.isNotBlank() && value.question.isNotBlank() && value.options.size in 2..20 && value.options.all { it.isNotBlank() } && value.options.map(::normalize).toSet().size == value.options.size && normalize(value.answer) in value.options.map(::normalize)
    private fun questionKey(question: String, options: List<String>) = sha256(normalize(question) + "\u001f" + options.map { normalize(optionText(it)) }.sorted().joinToString("\u001e"))
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun optionText(value: String) = value.replace('\u00a0', ' ').trim().replace(Regex("^\\d+[.)]\\s+"), "")
    private fun normalize(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().lowercase()
    private class WriteConflict : RuntimeException()
}
