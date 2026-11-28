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

/** One accepted answer as it is keyed in a routine file (`qa_answers.answer_key`). */
data class KeyedAnswer(val text: String, val answerKey: String)

/**
 * One question received from a remote Q&A service, identified there by [entryId] and by this file's
 * own identity: [bankName], [module] and [questionKey] (`qa_questions.question_key`).
 * [authoritative] marks an admin's correction: its answers replace every local answer.
 */
data class RemoteQuestionAnswer(
    val entryId: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<String>,
    val answers: List<KeyedAnswer>,
    val revision: Long,
    val retired: Boolean = false,
    val authoritative: Boolean = false,
)
data class RemoteApplyResult(val added: Int = 0, val updated: Int = 0, val removed: Int = 0, val skipped: Int = 0)

/** A learned answer the user confirmed, with the stable id that makes uploading it idempotent. */
data class PendingQuestionAnswer(
    val clientId: String,
    val bankName: String,
    val module: String,
    val question: String,
    val questionKey: String,
    val options: List<String>,
    val answer: String,
    val answerKey: String,
    val learnedAt: Long?,
    val reviewedAt: Long?,
)

/** The text normaliser used for question keys and answer keys in routine Q&A files. */
object QuestionAnswerText {
    fun normalize(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().lowercase()
    /** Removes a display-order label such as "2. " without changing meaningful numbers like 2.1. */
    fun optionText(value: String) = value.replace('\u00a0', ' ').trim().replace(Regex("^\\d+[.)]\\s+"), "")
    fun normalizedOption(value: String) = normalize(optionText(value))
    /** A file's `question_key`: SHA-256 (lowercase hex) of the normalised question, U+001F, then the sorted normalised options joined by U+001E. */
    fun questionKey(question: String, options: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest((normalize(question) + "\u001f" + options.map(::normalizedOption).sorted().joinToString("\u001e")).toByteArray())
            .joinToString("") { "%02x".format(it) }
    /** A file's `answer_key` for an answer saved by the app. */
    fun answerKey(answer: String): String = normalize(answer)
}

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
                    val sources = db.prepare("SELECT DISTINCT source FROM qa_answers WHERE question_id=?").use { check -> check.bindLong(1, id); buildSet { while (check.step()) add(check.getText(0)) } }
                    val source = when { "learned" in sources -> "learned"; "compass" in sources -> "compass"; else -> "source" }
                    add(StoredQuestionAnswer(rows.getText(1), rows.getText(2), optionsFor(db, id), answersFor(db, id), source))
                }
            }
        }
    }

    /**
     * Saves learned answers. [confirmed] marks answers the user confirmed themselves ("Save and
     * continue", or a review after an attempt): only those are ever uploaded for sharing.
     */
    suspend fun save(bankName: String, values: List<LearnedQuestionAnswer>, reviewed: Boolean, confirmed: Boolean = false): QuestionAnswerWriteResult {
        if (values.isEmpty() || values.any { !valid(it) }) return QuestionAnswerWriteResult.Invalid
        if (!prepare()) return QuestionAnswerWriteResult.Unavailable
        return mutex.withLock { withContext(Dispatchers.IO) {
            runCatching {
                open().use { db ->
                    db.prepare("BEGIN IMMEDIATE").use { it.step() }
                    try { values.forEach { saveOne(db, bankName, it, reviewed, confirmed) }; db.prepare("COMMIT").use { it.step() } }
                    catch (error: WriteConflict) { db.prepare("ROLLBACK").use { it.step() }; return@withContext QuestionAnswerWriteResult.Conflict }
                    catch (error: Throwable) { db.prepare("ROLLBACK").use { it.step() }; throw error }
                }
                QuestionAnswerWriteResult.Saved
            }.getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error; QuestionAnswerWriteResult.Unavailable }
        } }
    }

    private fun saveOne(db: SQLiteConnection, bank: String, value: LearnedQuestionAnswer, reviewed: Boolean, confirmed: Boolean) {
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
        // An admin's correction stands: saving the same answer changes nothing, and an automatic save
        // (a legacy import, quiz feedback, a reference) never replaces it. Only the user's own
        // confirmation can: that answer is used on this computer and queued for upload like any other
        // confirmed answer, so the admin sees the disagreement (Cosmos answers `conflict`).
        val corrected = isAuthoritative(db, bank, value.module, key) && existing.isNotEmpty()
        if (corrected && (existing == setOf(answerKey) || !confirmed)) return
        if (reviewed && ((previous != null && existing != setOf(previous)) || (previous == null && existing.isNotEmpty() && existing != setOf(answerKey)))) throw WriteConflict()
        if (!reviewed && existing.isNotEmpty() && existing != setOf(answerKey)) throw WriteConflict()
        // The same answer saved again keeps its upload identity, so it is never submitted twice.
        val kept = db.prepare("SELECT client_id,submitted_at,submit_status,confirmed_at FROM qa_answers WHERE question_id=? AND source='learned' AND answer_key=?").use { row ->
            row.bindLong(1, id); row.bindText(2, answerKey)
            if (row.step()) listOf<Any?>(row.textOrNull(0), row.longOrNull(1), row.textOrNull(2), row.longOrNull(3)) else null
        }
        db.prepare("DELETE FROM qa_answers WHERE question_id=?").use { row -> row.bindLong(1, id); row.step() }
        db.prepare("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at,client_id,submitted_at,submit_status,confirmed_at) VALUES (?,?,?,'learned',?,?,?,?,?,?)").use { row ->
            val now = System.currentTimeMillis(); row.bindLong(1, id); row.bindText(2, value.answer); row.bindText(3, answerKey); row.bindLong(4, now); if (reviewed) row.bindLong(5, now) else row.bindNull(5)
            row.bindTextOrNull(6, kept?.get(0) as String?)
            row.bindLongOrNull(7, kept?.get(1) as Long?); row.bindTextOrNull(8, kept?.get(2) as String?)
            // Confirmation is kept when the same answer is saved again by an automatic path.
            row.bindLongOrNull(9, if (confirmed && reviewed) now else kept?.get(3) as Long?); row.step()
        }
    }

    private fun optionsFor(db: SQLiteConnection, id: Long): List<String> = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { row -> row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) } }
    private fun answersFor(db: SQLiteConnection, id: Long): List<String> = db.prepare("SELECT answer_text FROM qa_answers WHERE question_id=? ORDER BY answer_key").use { row -> row.bindLong(1, id); buildList { while (row.step()) add(row.getText(0)) } }
    private suspend fun <T> read(block: (SQLiteConnection) -> T): T? = withContext(Dispatchers.IO) { if (!prepare()) null else runCatching { open().use(block) }.getOrNull() }
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
                open().use { installed -> QuestionAnswerSchema.upgrade(installed, databaseFile, backup = false) }
            } else {
                open().use { installed ->
                    QuestionAnswerSchema.upgrade(installed, databaseFile, backup = true)
                    installed.prepare("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)").use { it.step() }
                    requeueSuperseded(installed)
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

    /**
     * Earlier versions kept a confirmed answer that disagreed with an admin's correction on this computer
     * only (`submit_status = 'superseded'`). Such answers are now submitted, so those rows go back into the
     * upload queue, once per file: a metadata flag records that it was done.
     */
    private fun requeueSuperseded(db: SQLiteConnection) {
        val done = db.prepare("SELECT 1 FROM metadata WHERE key='$REQUEUED_KEY'").use { it.step() }
        if (done) return
        db.prepare("BEGIN IMMEDIATE").use { it.step() }
        try {
            db.prepare("UPDATE qa_answers SET submit_status=NULL,submitted_at=NULL WHERE source='learned' AND submit_status=?").use { row -> row.bindText(1, SUPERSEDED); row.step() }
            db.prepare("INSERT OR REPLACE INTO metadata(key,value) VALUES ('$REQUEUED_KEY','1')").use { it.step() }
            db.prepare("COMMIT").use { it.step() }
        } catch (error: Throwable) {
            runCatching { db.prepare("ROLLBACK").use { it.step() } }
            throw error
        }
    }

    /**
     * Adds current bundled questions without discarding answers learned in the user's copy. A question an
     * admin corrected is left exactly as the correction (or its retirement) left it.
     */
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
                    if (isAuthoritative(installed, bank, module, key)) continue
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
                    val hasLearned = installed.prepare("SELECT 1 FROM qa_answers WHERE question_id=? AND source IN ('learned','compass') LIMIT 1").use { row -> row.bindLong(1, installedId); row.step() }
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
    /** A value from this file's `sync_state` table, or null when unset or unavailable. */
    suspend fun syncValue(key: String): String? = read { db ->
        db.prepare("SELECT value FROM sync_state WHERE key=?").use { row -> row.bindText(1, key); if (row.step()) row.getText(0) else null }
    }

    suspend fun setSyncValue(key: String, value: String): Boolean = write { db -> putSyncValue(db, key, value) } != null

    /**
     * Applies remote entries (each under its own bank name) and stores [revisionKey] = [revision] in the same transaction,
     * so the saved cursor never runs ahead of the saved data.
     *
     * - A new question is added with `source = 'compass'` answers.
     * - A question that already has bundled or learned answers keeps them: local answers win.
     * - An authoritative entry (an admin's correction) replaces every local answer of its question, and
     *   the question stays authoritative: later entries for it, authoritative or not, are applied the same way.
     *   One exception: once the user has confirmed a different answer after that correction, the same
     *   revision arriving again (or an older one) leaves the user's answer in use; only a newer revision
     *   (the admin acted again) replaces it.
     * - A retired entry removes only the answers (and questions) that came from the remote service; a
     *   corrected question then stays empty rather than getting its old answer back.
     */
    suspend fun applyRemote(entries: List<RemoteQuestionAnswer>, revisionKey: String, revision: Long): RemoteApplyResult? = write { db ->
        var result = RemoteApplyResult()
        entries.forEach { entry -> result = applyOne(db, entry, result) }
        putSyncValue(db, revisionKey, revision.toString())
        result
    }

    /**
     * Confirmed learned answers not yet uploaded, oldest first. Each gets its permanent client id
     * the first time it's listed.
     */
    suspend fun pendingUploads(limit: Int): List<PendingQuestionAnswer>? = write { db ->
        val rows = db.prepare(
            "SELECT a.question_id,a.answer_key,a.client_id,q.module,q.question_text,a.answer_text,a.learned_at,a.reviewed_at,q.bank_name,q.question_key FROM qa_answers a " +
                "JOIN qa_questions q ON q.id=a.question_id WHERE $PENDING ORDER BY a.reviewed_at,a.question_id LIMIT ?",
        ).use { row ->
            row.bindLong(1, limit.toLong())
            buildList { while (row.step()) add(listOf(row.getLong(0), row.getText(1), row.textOrNull(2), row.getText(3), row.getText(4), row.getText(5), row.longOrNull(6), row.longOrNull(7), row.getText(8), row.getText(9))) }
        }
        rows.map { values ->
            val questionId = values[0] as Long
            val clientId = values[2] as String? ?: java.util.UUID.randomUUID().toString().also { id ->
                db.prepare("UPDATE qa_answers SET client_id=? WHERE question_id=? AND answer_key=?").use { update ->
                    update.bindText(1, id); update.bindLong(2, questionId); update.bindText(3, values[1] as String); update.step()
                }
            }
            PendingQuestionAnswer(clientId, values[8] as String, values[3] as String, values[4] as String, values[9] as String, optionsFor(db, questionId),
                values[5] as String, values[1] as String, values[6] as Long?, values[7] as Long?)
        }
    }

    suspend fun pendingUploadCount(): Int? = read { db ->
        db.prepare("SELECT count(*) FROM qa_answers a WHERE $PENDING").use { row -> row.step(); row.getLong(0).toInt() }
    }

    /** Records each uploaded answer's outcome ([statuses] maps client id to status). */
    suspend fun markUploads(statuses: Map<String, String>, at: Long): Boolean = write { db ->
        statuses.forEach { (clientId, status) ->
            db.prepare("UPDATE qa_answers SET submitted_at=?,submit_status=? WHERE client_id=?").use { row ->
                row.bindLong(1, at); row.bindText(2, status); row.bindText(3, clientId); row.step()
            }
        }
    } != null

    private fun applyOne(db: SQLiteConnection, entry: RemoteQuestionAnswer, result: RemoteApplyResult): RemoteApplyResult {
        val linked = db.prepare("SELECT id,bank_name,module,question_key FROM qa_questions WHERE compass_entry_id=?").use { row ->
            row.bindText(1, entry.entryId); buildList { while (row.step()) add(row.getLong(0) to listOf(row.getText(1), row.getText(2), row.getText(3))) }
        }
        val validOptions = entry.options.size in 2..20 && entry.options.all { optionText(it).isNotBlank() }
        // The keys are this file's own; one that doesn't match the question and options it arrived with
        // would never be found by an exact lookup, so such an entry is not stored.
        val usable = validOptions && entry.module.isNotBlank() && entry.question.isNotBlank() && entry.bankName.isNotBlank() &&
            entry.questionKey == questionKey(entry.question, entry.options) &&
            entry.answers.isNotEmpty() && entry.answers.all { answer -> answer.answerKey.isNotBlank() && entry.options.any { normalize(optionText(it)) == normalize(optionText(answer.text)) } }
        val identity = listOf(entry.bankName, entry.module, entry.questionKey)
        // Once corrected, a question is never downgraded by a later entry without the flag.
        val authoritative = entry.authoritative || isAuthoritative(db, entry.bankName, entry.module, entry.questionKey)
        var removed = 0
        // Retired, unusable, or moved to another identity: withdraw what this entry added before.
        linked.filter { (_, linkedIdentity) -> entry.retired || !usable || linkedIdentity != identity }.forEach { (id, _) -> if (unlinkRemote(db, id)) removed++ }
        if (entry.retired) return result.copy(removed = result.removed + removed)
        if (!usable) return result.copy(removed = result.removed + removed, skipped = result.skipped + 1)
        val existing = db.prepare("SELECT id FROM qa_questions WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, entry.bankName); row.bindText(2, entry.module); row.bindText(3, entry.questionKey); if (row.step()) row.getLong(0) else null
        }
        if (existing == null) {
            db.prepare("INSERT INTO qa_questions(bank_name,module,question_text,question_key,compass_entry_id,compass_revision,compass_created) VALUES (?,?,?,?,?,?,1)").use { row ->
                row.bindText(1, entry.bankName); row.bindText(2, entry.module); row.bindText(3, entry.question); row.bindText(4, entry.questionKey); row.bindText(5, entry.entryId); row.bindLong(6, entry.revision); row.step()
            }
            val id = db.prepare("SELECT last_insert_rowid()").use { row -> row.step(); row.getLong(0) }
            entry.options.forEachIndexed { index, option -> db.prepare("INSERT INTO qa_options(question_id,position,option_text) VALUES (?,?,?)").use { row ->
                row.bindLong(1, id); row.bindLong(2, (index + 1).toLong()); row.bindText(3, option); row.step()
            } }
            insertRemoteAnswers(db, id, entry)
            if (authoritative) recordAuthoritative(db, entry)
            return result.copy(added = result.added + 1, removed = result.removed + removed)
        }
        db.prepare("UPDATE qa_questions SET compass_entry_id=?,compass_revision=? WHERE id=?").use { row ->
            row.bindText(1, entry.entryId); row.bindLong(2, entry.revision); row.bindLong(3, existing); row.step()
        }
        if (authoritative) {
            // The user confirmed a different answer after this correction: it stays in use until the admin
            // changes the entry again (a newer revision).
            val corrected = authoritativeRevision(db, entry.bankName, entry.module, entry.questionKey)
            if (corrected != null && entry.revision <= corrected && hasConfirmedLearned(db, existing)) {
                return result.copy(skipped = result.skipped + 1, removed = result.removed + removed)
            }
            // Bundled, learned and earlier remote answers all give way; a learned answer removed here is never uploaded.
            db.prepare("DELETE FROM qa_answers WHERE question_id=?").use { row -> row.bindLong(1, existing); row.step() }
            insertRemoteAnswers(db, existing, entry)
            recordAuthoritative(db, entry)
            return result.copy(updated = result.updated + 1, removed = result.removed + removed)
        }
        val local = db.prepare("SELECT 1 FROM qa_answers WHERE question_id=? AND source<>'compass' LIMIT 1").use { row -> row.bindLong(1, existing); row.step() }
        if (local) return result.copy(skipped = result.skipped + 1, removed = result.removed + removed)
        db.prepare("DELETE FROM qa_answers WHERE question_id=? AND source='compass'").use { row -> row.bindLong(1, existing); row.step() }
        insertRemoteAnswers(db, existing, entry)
        return result.copy(updated = result.updated + 1, removed = result.removed + removed)
    }

    private fun insertRemoteAnswers(db: SQLiteConnection, questionId: Long, entry: RemoteQuestionAnswer) {
        entry.answers.distinctBy { it.answerKey }.forEach { answer ->
            db.prepare("INSERT OR IGNORE INTO qa_answers(question_id,answer_text,answer_key,source,compass_revision) VALUES (?,?,?,'compass',?)").use { row ->
                row.bindLong(1, questionId); row.bindText(2, answer.text); row.bindText(3, answer.answerKey); row.bindLong(4, entry.revision); row.step()
            }
        }
    }

    /** Removes remote answers from one question, and the question itself when the remote service added it and nothing else remains. */
    private fun unlinkRemote(db: SQLiteConnection, id: Long): Boolean {
        db.prepare("DELETE FROM qa_answers WHERE question_id=? AND source='compass'").use { row -> row.bindLong(1, id); row.step() }
        val created = db.prepare("SELECT compass_created FROM qa_questions WHERE id=?").use { row -> row.bindLong(1, id); row.step() && row.getLong(0) == 1L }
        val remaining = db.prepare("SELECT 1 FROM qa_answers WHERE question_id=? LIMIT 1").use { row -> row.bindLong(1, id); row.step() }
        if (created && !remaining) {
            db.prepare("DELETE FROM qa_options WHERE question_id=?").use { row -> row.bindLong(1, id); row.step() }
            db.prepare("DELETE FROM qa_questions WHERE id=?").use { row -> row.bindLong(1, id); row.step() }
        } else {
            db.prepare("UPDATE qa_questions SET compass_entry_id=NULL,compass_revision=NULL WHERE id=?").use { row -> row.bindLong(1, id); row.step() }
        }
        return true
    }

    private fun isAuthoritative(db: SQLiteConnection, bankName: String, module: String, questionKey: String): Boolean =
        db.prepare("SELECT 1 FROM qa_authoritative WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, bankName); row.bindText(2, module); row.bindText(3, questionKey); row.step()
        }

    private fun authoritativeRevision(db: SQLiteConnection, bankName: String, module: String, questionKey: String): Long? =
        db.prepare("SELECT revision FROM qa_authoritative WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, bankName); row.bindText(2, module); row.bindText(3, questionKey); if (row.step()) row.longOrNull(0) else null
        }

    private fun hasConfirmedLearned(db: SQLiteConnection, questionId: Long): Boolean =
        db.prepare("SELECT 1 FROM qa_answers WHERE question_id=? AND source='learned' AND confirmed_at IS NOT NULL LIMIT 1").use { row -> row.bindLong(1, questionId); row.step() }

    private fun recordAuthoritative(db: SQLiteConnection, entry: RemoteQuestionAnswer) {
        db.prepare("INSERT OR REPLACE INTO qa_authoritative(bank_name,module,question_key,entry_id,revision) VALUES (?,?,?,?,?)").use { row ->
            row.bindText(1, entry.bankName); row.bindText(2, entry.module); row.bindText(3, entry.questionKey); row.bindText(4, entry.entryId); row.bindLong(5, entry.revision); row.step()
        }
    }

    private fun putSyncValue(db: SQLiteConnection, key: String, value: String) {
        db.prepare("INSERT OR REPLACE INTO sync_state(key,value) VALUES (?,?)").use { row -> row.bindText(1, key); row.bindText(2, value); row.step() }
    }

    /** One write transaction, serialised with every other write to this file; null when unavailable. */
    private suspend fun <T> write(block: (SQLiteConnection) -> T): T? {
        if (!prepare()) return null
        return mutex.withLock { withContext(Dispatchers.IO) {
            runCatching {
                open().use { db ->
                    db.prepare("BEGIN IMMEDIATE").use { it.step() }
                    try { block(db).also { db.prepare("COMMIT").use { it.step() } } }
                    catch (error: Throwable) { runCatching { db.prepare("ROLLBACK").use { it.step() } }; throw error }
                }
            }.getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error; null }
        } }
    }

    /** Waits briefly for another writer (a routine or a sync) instead of failing at once. */
    private fun open(): SQLiteConnection = driver.open(databaseFile.absolutePath).also { db ->
        db.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
    }

    private fun androidx.sqlite.SQLiteStatement.textOrNull(index: Int) = if (isNull(index)) null else getText(index)
    private fun androidx.sqlite.SQLiteStatement.longOrNull(index: Int) = if (isNull(index)) null else getLong(index)
    private fun androidx.sqlite.SQLiteStatement.bindTextOrNull(index: Int, value: String?) = if (value == null) bindNull(index) else bindText(index, value)
    private fun androidx.sqlite.SQLiteStatement.bindLongOrNull(index: Int, value: Long?) = if (value == null) bindNull(index) else bindLong(index, value)

    private fun valid(value: LearnedQuestionAnswer) = value.module.isNotBlank() && value.question.isNotBlank() && value.options.size in 2..20 && value.options.all { it.isNotBlank() } && value.options.map(::normalize).toSet().size == value.options.size && normalize(value.answer) in value.options.map(::normalize)
    private fun questionKey(question: String, options: List<String>) = QuestionAnswerText.questionKey(question, options)
    private fun optionText(value: String) = QuestionAnswerText.optionText(value)
    private fun normalize(value: String) = QuestionAnswerText.normalize(value)
    private class WriteConflict : RuntimeException()

    private companion object {
        /**
         * `submit_status` earlier versions gave a confirmed answer that disagreed with an admin's correction, so it
         * was never uploaded. No longer written; [requeueSuperseded] puts such rows back in the upload queue.
         */
        const val SUPERSEDED = "superseded"
        const val REQUEUED_KEY = "superseded_requeued"
        /** Learned answers the user confirmed ("Save and continue" or a post-attempt review), not yet uploaded. */
        const val PENDING = "a.source='learned' AND a.reviewed_at IS NOT NULL AND a.confirmed_at IS NOT NULL AND a.submit_status IS NULL"
    }
}
