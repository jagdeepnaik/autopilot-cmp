package com.jagdeep.persistence.qa

import androidx.sqlite.SQLiteConnection
import java.io.File

/**
 * In-place, non-destructive upgrades of a routine Q&A file.
 *
 * Version 2 adds remote-sync bookkeeping: the `compass` answer source, the remote entry and revision
 * of a downloaded question, a stable `client_id`, submission state and user confirmation time
 * (`confirmed_at`) per learned answer, and a
 * `sync_state` table. SQLite can't change a CHECK constraint in place, so `qa_answers` is rebuilt.
 *
 * Version 3 adds `qa_authoritative`: the questions an admin corrected in the remote service. Their
 * remote answers replace every local answer, and the record outlives the question row, so a later
 * bundle merge or an automatic save never brings the old answer back. Since Cosmos became the source of truth for
 * every approved entry, the table records every question an applied entry owns, not only corrections (no schema change).
 *
 * Version 4 repairs keys: every `question_key` and `answer_key` is recomputed with [QuestionAnswerText],
 * the app's own rule. Older bundles were built with case folding, which turned the micro sign `µ` into
 * Greek `μ`, so the app never found those rows. A row whose recomputed key already belongs to another row
 * of the same bank name and module is merged into it ([mergeQuestion]), and `qa_authoritative` follows the
 * question's new key.
 *
 * Every step from the file's version up to [VERSION] runs inside one transaction, after a backup copy
 * of the file is written next to it.
 *
 * The `compass` source value and `compass_*` column names are shipped storage identifiers in users'
 * files; they keep their values for compatibility and change only through an approved migration.
 */
internal object QuestionAnswerSchema {
    const val VERSION = 4

    fun version(db: SQLiteConnection): Int = db.prepare("PRAGMA user_version").use { row -> if (row.step()) row.getLong(0).toInt() else 0 }

    /**
     * Upgrades [db] (the file [databaseFile]) to [VERSION]. [backup] is false only for a file that was
     * just copied from the bundle and holds no user data yet.
     */
    fun upgrade(db: SQLiteConnection, databaseFile: File, backup: Boolean) {
        val from = version(db)
        if (from >= VERSION) return
        if (backup) backupCopy(db, databaseFile)
        db.exec("BEGIN IMMEDIATE")
        try {
            if (from < 2) upgradeToV2(db)
            db.exec(AUTHORITATIVE_TABLE)
            if (from < 4) repairKeys(db)
            db.exec("PRAGMA user_version = $VERSION")
            db.exec("COMMIT")
        } catch (error: Throwable) {
            runCatching { db.exec("ROLLBACK") }
            throw error
        }
    }

    private fun upgradeToV2(db: SQLiteConnection) {
        val answersBefore = count(db, "qa_answers")
        val questionColumns = columns(db, "qa_questions")
        if ("compass_entry_id" !in questionColumns) db.exec("ALTER TABLE qa_questions ADD COLUMN compass_entry_id TEXT")
        if ("compass_revision" !in questionColumns) db.exec("ALTER TABLE qa_questions ADD COLUMN compass_revision INTEGER")
        if ("compass_created" !in questionColumns) db.exec("ALTER TABLE qa_questions ADD COLUMN compass_created INTEGER NOT NULL DEFAULT 0")
        db.exec("CREATE INDEX IF NOT EXISTS qa_questions_compass_entry ON qa_questions(compass_entry_id)")
        val answerColumns = columns(db, "qa_answers")
        fun existing(name: String) = if (name in answerColumns) name else "NULL"
        db.exec(ANSWERS_TABLE.replace("qa_answers", "qa_answers_v2"))
        db.exec(
            "INSERT INTO qa_answers_v2(question_id,answer_text,answer_key,source,learned_at,reviewed_at,compass_revision,client_id,submitted_at,submit_status,confirmed_at) " +
                "SELECT question_id,answer_text,answer_key," +
                "CASE WHEN source IN ('source','learned','compass') THEN source ELSE 'source' END," +
                "learned_at,reviewed_at,${existing("compass_revision")},${existing("client_id")},${existing("submitted_at")},${existing("submit_status")},${existing("confirmed_at")} FROM qa_answers",
        )
        check(count(db, "qa_answers_v2") == answersBefore) { "Answer count changed during upgrade" }
        db.exec("DROP TABLE qa_answers")
        db.exec("ALTER TABLE qa_answers_v2 RENAME TO qa_answers")
        db.exec("CREATE UNIQUE INDEX IF NOT EXISTS qa_answers_client_id ON qa_answers(client_id)")
        db.exec(SYNC_STATE_TABLE)
        db.exec("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    private class Question(val id: Long, val bank: String, val module: String, val text: String, val key: String)

    private fun repairKeys(db: SQLiteConnection) {
        repairAnswerKeys(db)
        val questions = db.prepare("SELECT id,bank_name,module,question_text,question_key FROM qa_questions ORDER BY id").use { row ->
            buildList { while (row.step()) add(Question(row.getLong(0), row.getText(1), row.getText(2), row.getText(3), row.getText(4))) }
        }
        questions.forEach { question ->
            val options = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { row ->
                row.bindLong(1, question.id); buildList { while (row.step()) add(row.getText(0)) }
            }
            val key = QuestionAnswerText.questionKey(question.text, options)
            if (key == question.key) return@forEach
            val target = db.prepare("SELECT id FROM qa_questions WHERE bank_name=? AND module=? AND question_key=?").use { row ->
                row.bindText(1, question.bank); row.bindText(2, question.module); row.bindText(3, key); if (row.step()) row.getLong(0) else null
            }
            if (target == null) {
                db.prepare("UPDATE qa_questions SET question_key=? WHERE id=?").use { row -> row.bindText(1, key); row.bindLong(2, question.id); row.step() }
            } else {
                val authoritative = isAuthoritative(db, question.bank, question.module, question.key) || isAuthoritative(db, question.bank, question.module, key)
                mergeQuestion(db, from = question.id, into = target, authoritative = authoritative)
            }
            moveAuthoritative(db, question.bank, question.module, question.key, key)
        }
    }

    /**
     * Every `answer_key` becomes `QuestionAnswerText.answerKey(answer_text)`. When two answers of one question
     * end up with the same key, the one the user confirmed (or saved most recently) is kept.
     */
    private fun repairAnswerKeys(db: SQLiteConnection) {
        val answers = db.prepare("SELECT rowid,question_id,answer_text,answer_key FROM qa_answers ORDER BY rowid").use { row ->
            buildList { while (row.step()) add(listOf<Any>(row.getLong(0), row.getLong(1), row.getText(2), row.getText(3))) }
        }
        answers.forEach { (rowId, questionId, text, stored) ->
            val key = QuestionAnswerText.answerKey(text as String)
            if (key == stored) return@forEach
            val clash = db.prepare("SELECT rowid FROM qa_answers WHERE question_id=? AND answer_key=?").use { row ->
                row.bindLong(1, questionId as Long); row.bindText(2, key); if (row.step()) row.getLong(0) else null
            }
            if (clash != null) {
                val keep = if (answerRank(db, rowId as Long) > answerRank(db, clash)) rowId else clash
                val drop = if (keep == clash) rowId as Long else clash
                db.prepare("DELETE FROM qa_answers WHERE rowid=?").use { row -> row.bindLong(1, drop); row.step() }
                if (keep == clash) return@forEach
            }
            db.prepare("UPDATE qa_answers SET answer_key=? WHERE rowid=?").use { row -> row.bindText(1, key); row.bindLong(2, rowId as Long); row.step() }
        }
    }

    /** Confirmed first, then one with an upload identity, then the most recently learned or reviewed. */
    private fun answerRank(db: SQLiteConnection, rowId: Long): Triple<Int, Int, Long> =
        db.prepare("SELECT confirmed_at IS NOT NULL, client_id IS NOT NULL, coalesce(reviewed_at,learned_at,0) FROM qa_answers WHERE rowid=?").use { row ->
            row.bindLong(1, rowId); row.step(); Triple(row.getLong(0).toInt(), row.getLong(1).toInt(), row.getLong(2))
        }

    private operator fun Triple<Int, Int, Long>.compareTo(other: Triple<Int, Int, Long>): Int =
        compareValuesBy(this, other, { it.first }, { it.second }, { it.third })

    /**
     * Merges question [from] into [into] (the same question under the app's key) and removes [from].
     * The answers kept are the set the store itself would keep: learned over bundled over remote, or, for an
     * admin-corrected question, remote over learned over bundled; on a tie the confirmed or more recent
     * set wins, then [into]'s. The remote link is kept, and the question stays remote-created only when both were.
     */
    private fun mergeQuestion(db: SQLiteConnection, from: Long, into: Long, authoritative: Boolean) {
        val priority = if (authoritative) mapOf("compass" to 3, "learned" to 2, "source" to 1) else mapOf("learned" to 3, "source" to 2, "compass" to 1)
        fun score(id: Long) = db.prepare(
            "SELECT source, confirmed_at IS NOT NULL, coalesce(reviewed_at,learned_at,0) FROM qa_answers WHERE question_id=?",
        ).use { row ->
            row.bindLong(1, id)
            var best = Triple(0, 0, 0L)
            while (row.step()) {
                val candidate = Triple(priority[row.getText(0)] ?: 0, row.getLong(1).toInt(), row.getLong(2))
                if (candidate > best) best = candidate
            }
            best
        }
        if (score(from) > score(into)) {
            db.prepare("DELETE FROM qa_answers WHERE question_id=?").use { row -> row.bindLong(1, into); row.step() }
            db.prepare("UPDATE qa_answers SET question_id=? WHERE question_id=?").use { row -> row.bindLong(1, into); row.bindLong(2, from); row.step() }
        }
        db.prepare(
            "UPDATE qa_questions SET " +
                "compass_revision=CASE WHEN compass_entry_id IS NULL THEN (SELECT compass_revision FROM qa_questions WHERE id=?1) ELSE compass_revision END," +
                "compass_entry_id=coalesce(compass_entry_id,(SELECT compass_entry_id FROM qa_questions WHERE id=?1))," +
                "compass_created=min(compass_created,(SELECT compass_created FROM qa_questions WHERE id=?1)) WHERE id=?2",
        ).use { row -> row.bindLong(1, from); row.bindLong(2, into); row.step() }
        listOf("qa_answers", "qa_options").forEach { table ->
            db.prepare("DELETE FROM $table WHERE question_id=?").use { row -> row.bindLong(1, from); row.step() }
        }
        db.prepare("DELETE FROM qa_questions WHERE id=?").use { row -> row.bindLong(1, from); row.step() }
    }

    /** Moves a correction record to the question's new key; when both exist, the later revision stays. */
    private fun moveAuthoritative(db: SQLiteConnection, bank: String, module: String, oldKey: String, newKey: String) {
        if (!isAuthoritative(db, bank, module, oldKey)) return
        db.prepare(
            "INSERT INTO qa_authoritative(bank_name,module,question_key,entry_id,revision) " +
                "SELECT bank_name,module,?3,entry_id,revision FROM qa_authoritative WHERE bank_name=?1 AND module=?2 AND question_key=?4 " +
                "ON CONFLICT(bank_name,module,question_key) DO UPDATE SET entry_id=excluded.entry_id,revision=excluded.revision WHERE excluded.revision>revision",
        ).use { row -> row.bindText(1, bank); row.bindText(2, module); row.bindText(3, newKey); row.bindText(4, oldKey); row.step() }
        db.prepare("DELETE FROM qa_authoritative WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, bank); row.bindText(2, module); row.bindText(3, oldKey); row.step()
        }
    }

    private fun isAuthoritative(db: SQLiteConnection, bank: String, module: String, key: String): Boolean =
        db.prepare("SELECT 1 FROM qa_authoritative WHERE bank_name=? AND module=? AND question_key=?").use { row ->
            row.bindText(1, bank); row.bindText(2, module); row.bindText(3, key); row.step()
        }

    /** A consistent copy of the user's file; an existing backup is never overwritten. */
    private fun backupCopy(db: SQLiteConnection, databaseFile: File) {
        val folder = databaseFile.absoluteFile.parentFile
        var target = File(folder, "${databaseFile.name}.before-v$VERSION.bak")
        if (target.exists()) target = File(folder, "${databaseFile.name}.before-v$VERSION-${System.currentTimeMillis()}.bak")
        db.prepare("VACUUM INTO ?").use { statement -> statement.bindText(1, target.absolutePath); statement.step() }
    }

    private fun columns(db: SQLiteConnection, table: String): Set<String> =
        db.prepare("PRAGMA table_info($table)").use { row -> buildSet { while (row.step()) add(row.getText(1)) } }

    private fun count(db: SQLiteConnection, table: String): Long = db.prepare("SELECT count(*) FROM $table").use { row -> row.step(); row.getLong(0) }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step() }

    private val ANSWERS_TABLE = """
        CREATE TABLE qa_answers (
          question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          answer_text TEXT NOT NULL, answer_key TEXT NOT NULL,
          source TEXT NOT NULL CHECK(source IN ('source','learned','compass')),
          learned_at INTEGER, reviewed_at INTEGER,
          compass_revision INTEGER, client_id TEXT, submitted_at INTEGER, submit_status TEXT, confirmed_at INTEGER,
          PRIMARY KEY(question_id, answer_key)
        )
    """.trimIndent()

    /** One row per corrected question, keyed by the file's own identity (it survives the question row). */
    private val AUTHORITATIVE_TABLE = """
        CREATE TABLE IF NOT EXISTS qa_authoritative (
          bank_name TEXT NOT NULL, module TEXT NOT NULL, question_key TEXT NOT NULL,
          entry_id TEXT NOT NULL, revision INTEGER NOT NULL,
          PRIMARY KEY(bank_name, module, question_key)
        )
    """.trimIndent()

    private const val SYNC_STATE_TABLE = "CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
}
