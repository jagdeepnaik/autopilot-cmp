package com.jagdeep.persistence.qa

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class QuestionAnswerSyncTest {
    private val root = createTempDirectory("autopilot-qa-sync-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    /** The schema every routine file shipped with before sync: answers limited to 'source' and 'learned'. */
    private fun oldSchemaFile(name: String): File = File(root, name).also { file ->
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            db.sql("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.sql("CREATE TABLE qa_questions (id INTEGER PRIMARY KEY, bank_name TEXT NOT NULL, module TEXT NOT NULL, question_text TEXT NOT NULL, question_key TEXT NOT NULL, UNIQUE(bank_name, module, question_key))")
            db.sql("CREATE TABLE qa_options (question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE, position INTEGER NOT NULL, option_text TEXT NOT NULL, PRIMARY KEY(question_id, position))")
            db.sql("CREATE TABLE qa_answers (question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE, answer_text TEXT NOT NULL, answer_key TEXT NOT NULL, source TEXT NOT NULL CHECK(source IN ('source','learned')), learned_at INTEGER, reviewed_at INTEGER, PRIMARY KEY(question_id, answer_key))")
            db.sql("INSERT INTO qa_questions VALUES(1,'bank.json','Module','Bundled?','${keyOf("Bundled?", listOf("A", "B"))}')")
            db.sql("INSERT INTO qa_options VALUES(1,1,'A')"); db.sql("INSERT INTO qa_options VALUES(1,2,'B')")
            db.sql("INSERT INTO qa_answers VALUES(1,'B','b','source',NULL,NULL)")
            db.sql("INSERT INTO qa_questions VALUES(2,'bank.json','Module','Learned?','${keyOf("Learned?", listOf("Yes", "No"))}')")
            db.sql("INSERT INTO qa_options VALUES(2,1,'Yes'), (2,2,'No')")
            db.sql("INSERT INTO qa_answers VALUES(2,'No','no','learned',100,200)")
        }
    }

    private fun store(installed: File, bundled: File = installed) = SqliteQuestionAnswerStore(installed) { bundled.inputStream() }

    @Test fun given_an_old_schema_user_file_when_opened_then_it_is_upgraded_in_place_with_a_backup_and_no_data_lost() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val installed = File(root, "smy.db")
        bundle.copyTo(installed)
        // The user's copy has its own learned answer that the bundle doesn't.
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            db.sql("INSERT INTO qa_questions VALUES(3,'bank.json','Module','Mine?','${keyOf("Mine?", listOf("Up", "Down"))}')")
            db.sql("INSERT INTO qa_options VALUES(3,1,'Up'), (3,2,'Down')")
            db.sql("INSERT INTO qa_answers VALUES(3,'Up','up','learned',300,NULL)")
        }
        val entries = store(installed, bundle).entries("bank.json")!!
        assertEquals(setOf("Bundled?", "Learned?", "Mine?"), entries.map { it.question }.toSet())
        assertEquals(listOf("Up"), entries.single { it.question == "Mine?" }.answers)
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            assertEquals(QuestionAnswerSchema.VERSION, QuestionAnswerSchema.version(db))
            assertEquals(3L, db.long("SELECT count(*) FROM qa_answers"))
            assertEquals(200L, db.long("SELECT reviewed_at FROM qa_answers WHERE question_id=2"))
            // The rebuilt table accepts the new source.
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source) VALUES(1,'A','a','compass')")
        }
        val backup = File(root, "smy.db.before-v2.bak")
        assertTrue(backup.isFile)
        BundledSQLiteDriver().open(backup.absolutePath).use { db -> assertEquals(3L, db.long("SELECT count(*) FROM qa_answers")) }
    }

    @Test fun given_a_fresh_bundled_copy_when_opened_then_it_is_upgraded_without_a_backup() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val installed = File(root, "olp.db")
        // The bundle's reviewed learned answer predates confirmation tracking, so it is never uploaded.
        assertEquals(0, store(installed, bundle).pendingUploadCount())
        BundledSQLiteDriver().open(installed.absolutePath).use { db -> assertEquals(QuestionAnswerSchema.VERSION, QuestionAnswerSchema.version(db)) }
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".bak") })
    }

    @Test fun given_remote_entries_when_applied_then_new_questions_are_added_and_local_answers_win() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val store = store(File(root, "smy.db"), bundle)
        val result = store.applyRemote(listOf(
            remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 10),
            // Same question and options as the bundled row, with a different answer: the bundled answer stays.
            remote("e2", "Bundled?", listOf("A", "B"), listOf("A"), 11),
            // An answer that isn't one of the options is never stored.
            remote("e3", "Broken?", listOf("X", "Y"), listOf("Z"), 12),
        ), "revision", 12)!!
        assertEquals(RemoteApplyResult(added = 1, skipped = 2), result)
        val entries = store.entries("bank.json")!!
        assertEquals(listOf("Blue"), entries.single { it.question == "Remote?" }.answers)
        assertEquals("compass", entries.single { it.question == "Remote?" }.source)
        assertEquals(listOf("B"), entries.single { it.question == "Bundled?" }.answers)
        assertTrue(entries.none { it.question == "Broken?" })
        assertEquals("12", store.syncValue("revision"))

        // A newer revision of the remote entry replaces only the remote answer.
        assertEquals(RemoteApplyResult(updated = 1), store.applyRemote(listOf(remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Red"), 20)), "revision", 20))
        assertEquals(listOf("Red"), store.entries("bank.json")!!.single { it.question == "Remote?" }.answers)
    }

    @Test fun given_a_retired_entry_when_applied_then_only_remote_rows_are_removed() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        store.applyRemote(listOf(
            remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 10),
            remote("e2", "Bundled?", listOf("A", "B"), listOf("B"), 11),
        ), "revision", 11)
        val result = store.applyRemote(listOf(
            remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 30, retired = true),
            remote("e2", "Bundled?", listOf("A", "B"), listOf("B"), 31, retired = true),
        ), "revision", 31)!!
        assertEquals(2, result.removed)
        val entries = store.entries("bank.json")!!
        assertTrue(entries.none { it.question == "Remote?" })
        assertEquals(listOf("B"), entries.single { it.question == "Bundled?" }.answers)
        assertEquals(listOf("No"), entries.single { it.question == "Learned?" }.answers)
    }

    @Test fun given_confirmed_learned_answers_when_listed_and_marked_then_ids_are_stable_and_uploads_happen_once() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        // Unreviewed references, and reviewed answers the user didn't confirm (quiz feedback, imports), are never uploaded.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Reference?", listOf("One", "Two"), "One")), reviewed = false)
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Quiz?", listOf("One", "Two"), "One")), reviewed = true)
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Confirmed?", listOf("One", "Two"), "Two")), reviewed = true, confirmed = true)
        val first = store.pendingUploads(100)!!
        val confirmed = first.single()
        assertEquals("Confirmed?", confirmed.question)
        assertEquals(1, store.pendingUploadCount())
        assertEquals("bank.json", confirmed.bankName)
        assertEquals(keyOf("Confirmed?", listOf("One", "Two")), confirmed.questionKey)
        assertEquals("two", confirmed.answerKey)
        assertEquals(listOf("One", "Two"), confirmed.options)
        assertEquals("Two", confirmed.answer)
        assertNotNull(confirmed.reviewedAt)
        // Listing again returns the same id.
        assertEquals(confirmed.clientId, store.pendingUploads(100)!!.single().clientId)
        // Saving the same answer again (even by an automatic path) keeps the id and the confirmation.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Confirmed?", listOf("One", "Two"), "Two", previousAnswer = "Two")), reviewed = true)
        assertEquals(confirmed.clientId, store.pendingUploads(100)!!.single().clientId)

        assertTrue(store.markUploads(mapOf(confirmed.clientId to "pending"), at = 1_000))
        assertEquals(0, store.pendingUploadCount())
        assertTrue(store.pendingUploads(100)!!.isEmpty())
        // The same answer confirmed again after upload stays sent.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Confirmed?", listOf("One", "Two"), "Two", previousAnswer = "Two")), reviewed = true, confirmed = true)
        assertEquals(0, store.pendingUploadCount())
        // A corrected answer is a new submission with a new id once the user confirms it.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Confirmed?", listOf("One", "Two"), "One", previousAnswer = "Two")), reviewed = true, confirmed = true)
        val corrected = store.pendingUploads(100)!!.single()
        assertEquals("One", corrected.answer)
        assertNotEquals(confirmed.clientId, corrected.clientId)
    }

    @Test fun given_an_entry_whose_key_does_not_match_its_question_when_applied_then_it_is_skipped() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        val forged = remote("e5", "Other?", listOf("A", "B"), listOf("A"), 3).copy(questionKey = "0".repeat(64))
        assertEquals(RemoteApplyResult(skipped = 1), store.applyRemote(listOf(forged), "revision", 3))
        // An entry for another bank name in the same file goes under that bank name.
        assertEquals(RemoteApplyResult(added = 1), store.applyRemote(listOf(remote("e6", "Elsewhere?", listOf("A", "B"), listOf("B"), 4, bank = "other.json")), "revision", 4))
        assertEquals(listOf("B"), store.entries("other.json")!!.single().answers)
        assertTrue(store.entries("bank.json")!!.none { it.question == "Elsewhere?" })
    }

    @Test fun given_an_upgraded_file_when_the_bundle_changes_then_remote_answers_are_kept() = runBlocking {
        var bundle = oldSchemaFile("bundle.db")
        val store = SqliteQuestionAnswerStore(File(root, "smy.db")) { bundle.inputStream() }
        store.applyRemote(listOf(remote("e9", "Later?", listOf("P", "Q"), listOf("Q"), 5)), "revision", 5)
        val updated = File(root, "bundle2.db")
        bundle.copyTo(updated)
        BundledSQLiteDriver().open(updated.absolutePath).use { db ->
            db.sql("INSERT INTO qa_questions VALUES(9,'bank.json','Module','Later?','${keyOf("Later?", listOf("P", "Q"))}')")
            db.sql("INSERT INTO qa_options VALUES(9,1,'P'), (9,2,'Q')")
            db.sql("INSERT INTO qa_answers VALUES(9,'P','p','source',NULL,NULL)")
        }
        bundle = updated
        assertEquals(listOf("Q"), store.entries("bank.json")!!.single { it.question == "Later?" }.answers)
        assertNull(store.syncValue("missing"))
    }

    /** A remote entry carrying this file's own keys, as the remote service returns them. */
    private fun remote(id: String, question: String, options: List<String>, answers: List<String>, revision: Long, retired: Boolean = false, bank: String = "bank.json") =
        RemoteQuestionAnswer(id, bank, "Module", question, keyOf(question, options), options, answers.map { KeyedAnswer(it, QuestionAnswerText.normalize(it)) }, revision, retired)

    private fun keyOf(question: String, options: List<String>): String {
        val payload = QuestionAnswerText.normalize(question) + "\u001f" + options.map(QuestionAnswerText::normalizedOption).sorted().joinToString("\u001e")
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun SQLiteConnection.sql(value: String) = prepare(value).use { it.step() }
    private fun SQLiteConnection.long(value: String) = prepare(value).use { it.step(); it.getLong(0) }
}
