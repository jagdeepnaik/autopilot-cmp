package com.jagdeep.persistence.qa

import androidx.sqlite.SQLiteConnection
import java.io.File

/**
 * In-place, non-destructive upgrades of a routine Q&A file.
 *
 * Version 2 adds remote-sync bookkeeping: the `compass` answer source, the remote entry and revision
 * of a downloaded question, a stable `client_id`, submission state and user confirmation time
 * (`confirmed_at`) per learned answer, and a
 * `sync_state` table. SQLite can't change a CHECK constraint in place, so `qa_answers` is rebuilt
 * inside one transaction after a backup copy of the file is written next to it.
 *
 * The `compass` source value and `compass_*` column names are shipped storage identifiers in users'
 * files; they keep their values for compatibility and change only through an approved migration.
 */
internal object QuestionAnswerSchema {
    const val VERSION = 2

    fun version(db: SQLiteConnection): Int = db.prepare("PRAGMA user_version").use { row -> if (row.step()) row.getLong(0).toInt() else 0 }

    /**
     * Upgrades [db] (the file [databaseFile]) to [VERSION]. [backup] is false only for a file that was
     * just copied from the bundle and holds no user data yet.
     */
    fun upgrade(db: SQLiteConnection, databaseFile: File, backup: Boolean) {
        if (version(db) >= VERSION) return
        if (backup) backupCopy(db, databaseFile)
        db.exec("BEGIN IMMEDIATE")
        try {
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
            db.exec("PRAGMA user_version = $VERSION")
            db.exec("COMMIT")
        } catch (error: Throwable) {
            runCatching { db.exec("ROLLBACK") }
            throw error
        }
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

    private const val SYNC_STATE_TABLE = "CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
}
