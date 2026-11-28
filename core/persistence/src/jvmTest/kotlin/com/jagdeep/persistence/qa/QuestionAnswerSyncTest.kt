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
        val backup = File(root, "smy.db.before-v${QuestionAnswerSchema.VERSION}.bak")
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

    @Test fun given_remote_entries_when_applied_then_new_questions_are_added_and_cosmos_answers_replace_local_ones() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val store = store(File(root, "smy.db"), bundle)
        val result = store.applyRemote(listOf(
            remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 10),
            // Same question and options as the bundled row, with a different answer: Cosmos's answer replaces it.
            remote("e2", "Bundled?", listOf("A", "B"), listOf("A"), 11),
            // An answer that isn't one of the options is never stored.
            remote("e3", "Broken?", listOf("X", "Y"), listOf("Z"), 12),
        ), "revision", 12)!!
        assertEquals(RemoteApplyResult(added = 1, updated = 1, skipped = 1), result)
        val entries = store.entries("bank.json")!!
        assertEquals(listOf("Blue"), entries.single { it.question == "Remote?" }.answers)
        assertEquals("compass", entries.single { it.question == "Remote?" }.source)
        assertEquals(listOf("A"), entries.single { it.question == "Bundled?" }.answers)
        assertEquals("compass", entries.single { it.question == "Bundled?" }.source)
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
        // The entry replaced the bundled row, so retiring it leaves the question empty: no bundled answer comes back.
        assertEquals(emptyList(), entries.single { it.question == "Bundled?" }.answers)
        // A question Cosmos never had keeps its learned answer.
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

    @Test fun given_a_version_2_user_file_when_opened_then_it_is_upgraded_to_the_current_version_with_a_backup_and_no_data_lost() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val installed = File(root, "smy.db")
        bundle.copyTo(installed)
        // A file exactly as version 2 left it, with a remote answer and a sync cursor of its own.
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            QuestionAnswerSchema.upgrade(db, installed, backup = false)
            db.sql("DROP TABLE qa_authoritative"); db.sql("PRAGMA user_version = 2")
            db.sql("INSERT INTO qa_questions(id,bank_name,module,question_text,question_key,compass_entry_id,compass_revision,compass_created) VALUES(3,'bank.json','Module','Remote?','${keyOf("Remote?", listOf("Red", "Blue"))}','e1',7,1)")
            db.sql("INSERT INTO qa_options VALUES(3,1,'Red'), (3,2,'Blue')")
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,compass_revision) VALUES(3,'Blue','blue','compass',7)")
            db.sql("INSERT INTO sync_state VALUES('revision','7')")
        }
        val entries = store(installed, bundle).entries("bank.json")!!
        assertEquals(listOf("Blue"), entries.single { it.question == "Remote?" }.answers)
        // The sync cursor is cleared once, so every entry is downloaded again under the current rule.
        assertNull(store(installed, bundle).syncValue("revision"))
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            assertEquals(QuestionAnswerSchema.VERSION, QuestionAnswerSchema.version(db))
            assertEquals(3L, db.long("SELECT count(*) FROM qa_answers"))
            assertEquals(0L, db.long("SELECT count(*) FROM qa_authoritative"))
        }
        val backup = File(root, "smy.db.before-v${QuestionAnswerSchema.VERSION}.bak")
        assertTrue(backup.isFile)
        BundledSQLiteDriver().open(backup.absolutePath).use { db ->
            assertEquals(2, QuestionAnswerSchema.version(db))
            assertEquals(3L, db.long("SELECT count(*) FROM qa_answers"))
        }
    }

    @Test fun given_bundled_learned_and_remote_answers_when_an_approved_entry_is_applied_then_its_answers_replace_them_all() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        // A confirmed learned answer waiting to be uploaded, and an earlier remote answer.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Mine?", listOf("Up", "Down"), "Up")), reviewed = true, confirmed = true)
        store.applyRemote(listOf(remote("e4", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 5)), "revision", 5)
        assertEquals(1, store.pendingUploadCount())
        val result = store.applyRemote(listOf(
            // Not one of them is flagged authoritative: Cosmos is the source of truth for every approved entry.
            remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10),
            remote("e2", "Learned?", listOf("Yes", "No"), listOf("Yes"), 11),
            remote("e3", "Mine?", listOf("Up", "Down"), listOf("Down"), 12),
            remote("e4", "Remote?", listOf("Red", "Blue"), listOf("Red"), 13),
        ), "revision", 13)!!
        assertEquals(RemoteApplyResult(updated = 4), result)
        val entries = store.entries("bank.json")!!.associateBy { it.question }
        assertEquals(listOf("A"), entries.getValue("Bundled?").answers)
        assertEquals(listOf("Yes"), entries.getValue("Learned?").answers)
        assertEquals(listOf("Down"), entries.getValue("Mine?").answers)
        assertEquals(listOf("Red"), entries.getValue("Remote?").answers)
        assertTrue(entries.values.all { it.source == "compass" })
        // The learned answer the correction replaced is never uploaded.
        assertEquals(0, store.pendingUploadCount())
        assertTrue(store.pendingUploads(100)!!.isEmpty())
    }

    @Test fun given_a_synced_question_when_the_bundle_changes_or_the_app_starts_again_then_the_cosmos_answer_is_kept() = runBlocking {
        var bundle = oldSchemaFile("bundle.db")
        val installed = File(root, "smy.db")
        val store = SqliteQuestionAnswerStore(installed) { bundle.inputStream() }
        store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10)), "revision", 10)
        bundle = changedBundle(bundle)
        assertEquals(listOf("A"), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        // A new start: a fresh store on the same file, and the legacy import's automatic save of the old answer.
        val restarted = SqliteQuestionAnswerStore(installed) { bundle.inputStream() }
        assertEquals(QuestionAnswerWriteResult.Saved,
            restarted.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B", previousAnswer = "A")), reviewed = true))
        assertEquals(listOf("A"), restarted.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        assertEquals(0, restarted.pendingUploadCount())
    }

    @Test fun given_a_corrected_question_when_a_later_entry_without_the_flag_arrives_then_it_stays_authoritative() = runBlocking {
        var bundle = oldSchemaFile("bundle.db")
        val store = SqliteQuestionAnswerStore(File(root, "smy.db")) { bundle.inputStream() }
        store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10, authoritative = true)), "revision", 10)
        // A user's own learned answer after the correction still gives way to the next remote revision.
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B", previousAnswer = "A")), reviewed = true, confirmed = true)
        assertEquals(RemoteApplyResult(updated = 1), store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 20)), "revision", 20))
        assertEquals(listOf("A"), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        bundle = changedBundle(bundle)
        val entry = store.entries("bank.json")!!.single { it.question == "Bundled?" }
        assertEquals(listOf("A"), entry.answers)
        assertEquals("compass", entry.source)
    }

    @Test fun given_a_corrected_question_when_the_user_confirms_a_different_answer_then_it_is_used_locally_and_uploaded_once() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10, authoritative = true)), "revision", 10)
        assertEquals(QuestionAnswerWriteResult.Saved,
            store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B", previousAnswer = "A")), reviewed = true, confirmed = true))
        assertEquals(listOf("B"), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        // It disagrees with the admin's correction and is submitted, so the admin sees the conflict.
        val pending = store.pendingUploads(100)!!.single()
        assertEquals("Bundled?", pending.question)
        assertEquals("b", pending.answerKey)
        assertTrue(store.markUploads(mapOf(pending.clientId to "conflict"), at = 1_000))
        assertEquals(0, store.pendingUploadCount())
        assertTrue(store.pendingUploads(100)!!.isEmpty())
        assertEquals(listOf("B"), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
    }

    @Test fun given_a_corrected_question_when_an_automatic_save_disagrees_then_nothing_is_stored_or_queued() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10, authoritative = true)), "revision", 10)
        // A legacy import or quiz feedback (reviewed, not confirmed) and a reference capture (not reviewed).
        assertEquals(QuestionAnswerWriteResult.Saved,
            store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B", previousAnswer = "A")), reviewed = true))
        assertEquals(QuestionAnswerWriteResult.Saved,
            store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B")), reviewed = false))
        val entry = store.entries("bank.json")!!.single { it.question == "Bundled?" }
        assertEquals(listOf("A"), entry.answers)
        assertEquals("compass", entry.source)
        assertEquals(0, store.pendingUploadCount())
    }

    @Test fun given_a_confirmed_disagreeing_answer_when_the_same_revision_arrives_again_then_the_answer_stays_until_a_newer_revision() = runBlocking {
        val store = store(File(root, "smy.db"), oldSchemaFile("bundle.db"))
        store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10)), "revision", 10)
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Bundled?", listOf("A", "B"), "B", previousAnswer = "A")), reviewed = true, confirmed = true)
        // A full sync downloads the same entry again: the user's confirmed answer stays in use and queued.
        assertEquals(RemoteApplyResult(skipped = 1), store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10)), "revision", 10))
        assertEquals(listOf("B"), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        assertEquals(1, store.pendingUploadCount())
        // The admin acts (a new revision, for example rejecting the conflict): it replaces the user's answer.
        assertEquals(RemoteApplyResult(updated = 1), store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 11)), "revision", 11))
        val entry = store.entries("bank.json")!!.single { it.question == "Bundled?" }
        assertEquals(listOf("A"), entry.answers)
        assertEquals("compass", entry.source)
        assertEquals(0, store.pendingUploadCount())
    }

    @Test fun given_a_file_with_superseded_answers_from_an_earlier_version_when_opened_then_they_are_queued_once() = runBlocking {
        val installed = File(root, "smy.db")
        val bundle = oldSchemaFile("bundle.db")
        store(installed, bundle).applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10, authoritative = true)), "revision", 10)
        // What an earlier version wrote for a confirmed answer that disagreed with the correction.
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            db.sql("DELETE FROM qa_answers WHERE question_id=(SELECT id FROM qa_questions WHERE question_text='Bundled?')")
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at,submitted_at,submit_status,confirmed_at) " +
                "SELECT id,'B','b','learned',100,100,100,'superseded',100 FROM qa_questions WHERE question_text='Bundled?'")
        }
        val restarted = store(installed, bundle)
        val pending = restarted.pendingUploads(100)!!.single()
        assertEquals("b", pending.answerKey)
        assertTrue(restarted.markUploads(mapOf(pending.clientId to "conflict"), at = 1_000))
        // Later starts never queue it again.
        val again = store(installed, bundle)
        assertEquals(0, again.pendingUploadCount())
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            assertEquals("conflict", db.text("SELECT submit_status FROM qa_answers WHERE answer_key='b' AND source='learned'"))
            assertEquals(0L, db.long("SELECT count(*) FROM qa_answers WHERE submit_status='superseded'"))
        }
    }

    @Test fun given_a_retired_entry_when_applied_then_the_question_is_left_without_its_old_bundled_answer() = runBlocking {
        var bundle = oldSchemaFile("bundle.db")
        val store = SqliteQuestionAnswerStore(File(root, "smy.db")) { bundle.inputStream() }
        store.applyRemote(listOf(
            remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10),
            remote("e2", "New?", listOf("P", "Q"), listOf("Q"), 11),
        ), "revision", 11)
        val result = store.applyRemote(listOf(
            remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 20, retired = true),
            remote("e2", "New?", listOf("P", "Q"), listOf("Q"), 21, retired = true),
        ), "revision", 21)!!
        assertEquals(2, result.removed)
        assertEquals(emptyList(), store.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        assertTrue(store.entries("bank.json")!!.none { it.question == "New?" })
        // A later bundle that carries both questions brings back neither answer.
        bundle = changedBundle(bundle) { db ->
            db.sql("INSERT INTO qa_questions VALUES(9,'bank.json','Module','New?','${keyOf("New?", listOf("P", "Q"))}')")
            db.sql("INSERT INTO qa_options VALUES(9,1,'P'), (9,2,'Q')")
            db.sql("INSERT INTO qa_answers VALUES(9,'P','p','source',NULL,NULL)")
        }
        val entries = store.entries("bank.json")!!
        assertEquals(emptyList(), entries.single { it.question == "Bundled?" }.answers)
        assertTrue(entries.none { it.question == "New?" })
        assertEquals(listOf("No"), entries.single { it.question == "Learned?" }.answers)
    }

    @Test fun given_learned_answers_for_questions_cosmos_lacks_when_a_full_download_is_applied_then_they_are_kept_and_queued() = runBlocking {
        var bundle = oldSchemaFile("bundle.db")
        val store = SqliteQuestionAnswerStore(File(root, "smy.db")) { bundle.inputStream() }
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Mine?", listOf("Up", "Down"), "Up")), reviewed = true, confirmed = true)
        store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Seen?", listOf("Here", "There"), "There")), reviewed = false)
        // Cosmos has only the bundled question.
        assertEquals(RemoteApplyResult(updated = 1), store.applyRemote(listOf(remote("e1", "Bundled?", listOf("A", "B"), listOf("A"), 10)), "revision", 10))
        val entries = store.entries("bank.json")!!.associateBy { it.question }
        assertEquals(listOf("A"), entries.getValue("Bundled?").answers)
        assertEquals(listOf("Up"), entries.getValue("Mine?").answers)
        assertEquals(listOf("There"), entries.getValue("Seen?").answers)
        assertEquals(listOf("No"), entries.getValue("Learned?").answers)
        assertEquals("Mine?", store.pendingUploads(100)!!.single().question)
        // A later bundle merge leaves both the Cosmos answer and the learned ones alone.
        bundle = changedBundle(bundle)
        val merged = store.entries("bank.json")!!.associateBy { it.question }
        assertEquals(listOf("A"), merged.getValue("Bundled?").answers)
        assertEquals("compass", merged.getValue("Bundled?").source)
        assertEquals(listOf("Up"), merged.getValue("Mine?").answers)
        assertEquals(1, store.pendingUploadCount())
    }

    @Test fun given_a_file_synced_under_the_earlier_rule_when_opened_then_its_cursor_is_cleared_once_and_answers_kept() = runBlocking {
        val bundle = oldSchemaFile("bundle.db")
        val installed = File(root, "smy.db")
        store(installed, bundle).applyRemote(listOf(remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 7)), "revision", 7)
        // What an earlier version left: the flag unset and a cursor past an entry whose bundled answer won.
        BundledSQLiteDriver().open(installed.absolutePath).use { db -> db.sql("DELETE FROM metadata WHERE key='cosmos_owns_entries'") }
        val restarted = store(installed, bundle)
        assertNull(restarted.syncValue("revision"))
        assertEquals(listOf("Blue"), restarted.entries("bank.json")!!.single { it.question == "Remote?" }.answers)
        // Downloading everything again applies the current rule; the new cursor then stays.
        restarted.applyRemote(listOf(remote("e2", "Bundled?", listOf("A", "B"), listOf("A"), 5), remote("e1", "Remote?", listOf("Red", "Blue"), listOf("Blue"), 7)), "revision", 7)
        assertEquals(listOf("A"), restarted.entries("bank.json")!!.single { it.question == "Bundled?" }.answers)
        assertEquals("7", store(installed, bundle).syncValue("revision"))
    }

    /** A file exactly as version 3 left it; [change] adds the rows under test. */
    private fun version3File(name: String, change: (SQLiteConnection) -> Unit): File = File(root, name).also { file ->
        oldSchemaFile("v3-source.db").copyTo(file)
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            QuestionAnswerSchema.upgrade(db, file, backup = false)
            db.sql("PRAGMA user_version = 3")
            change(db)
        }
    }

    @Test fun given_a_version_3_file_with_a_case_folded_key_when_opened_then_the_key_is_repaired_and_the_learned_answer_kept() = runBlocking {
        val options = listOf("4 \u00b5F", "8 \u00b5F")
        val question = "What is C(s) in \u00b5F?"
        val installed = version3File("smy.db") { db ->
            db.sql("INSERT INTO qa_questions(id,bank_name,module,question_text,question_key) VALUES(5,'bank.json','Module','$question','${foldedKeyOf(question, options)}')")
            db.sql("INSERT INTO qa_options VALUES(5,1,'${options[0]}'), (5,2,'${options[1]}')")
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at,client_id,confirmed_at) VALUES(5,'4 \u00b5F','4 \u03bcf','learned',100,200,'c-1',200)")
        }
        val store = store(installed, oldSchemaFile("bundle.db"))
        assertEquals(listOf("4 \u00b5F"), store.entries("bank.json")!!.single { it.question == question }.answers)
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            assertEquals(4, QuestionAnswerSchema.version(db))
            assertEquals(QuestionAnswerText.questionKey(question, options), db.text("SELECT question_key FROM qa_questions WHERE id=5"))
            assertEquals("4 \u00b5f", db.text("SELECT answer_key FROM qa_answers WHERE question_id=5"))
            assertEquals(1L, db.long("SELECT count(*) FROM qa_answers WHERE question_id=5 AND client_id='c-1' AND learned_at=100 AND reviewed_at=200 AND confirmed_at=200"))
        }
        // The confirmed answer is uploaded under the app's keys.
        val pending = store.pendingUploads(10)!!.single()
        assertEquals(QuestionAnswerText.questionKey(question, options) to "4 \u00b5f", pending.questionKey to pending.answerKey)
        // The app's own lookup now finds the row: saving the same answer again is no conflict and adds no question.
        assertEquals(QuestionAnswerWriteResult.Saved, store.save("bank.json", listOf(LearnedQuestionAnswer("Module", question, options, "4 \u00b5F")), reviewed = false))
        assertEquals(1, store.entries("bank.json")!!.count { it.question == question })
        val backup = File(root, "smy.db.before-v4.bak")
        assertTrue(backup.isFile)
        BundledSQLiteDriver().open(backup.absolutePath).use { db ->
            assertEquals(3, QuestionAnswerSchema.version(db))
            assertEquals(foldedKeyOf(question, options), db.text("SELECT question_key FROM qa_questions WHERE id=5"))
        }
    }

    @Test fun given_a_version_3_file_with_a_correctly_keyed_duplicate_when_opened_then_they_merge_and_learned_answers_win() = runBlocking {
        val options = listOf("4 \u00b5F", "8 \u00b5F")
        val first = "Bundled row folded, learned row correct: \u00b5F?"
        val second = "Learned row folded, bundled row correct: \u00b5F?"
        val installed = version3File("seagull.db") { db ->
            listOf(first to 10, second to 20).forEach { (question, id) ->
                db.sql("INSERT INTO qa_questions(id,bank_name,module,question_text,question_key) VALUES($id,'bank.json','Module','$question','${foldedKeyOf(question, options)}')")
                db.sql("INSERT INTO qa_questions(id,bank_name,module,question_text,question_key,compass_entry_id,compass_revision) VALUES(${id + 1},'bank.json','Module','$question','${keyOf(question, options)}','e$id',3)")
                db.sql("INSERT INTO qa_options VALUES($id,1,'${options[0]}'), ($id,2,'${options[1]}'), (${id + 1},1,'${options[0]}'), (${id + 1},2,'${options[1]}')")
            }
            // First question: the case-folded bundled row and the app's own learned, confirmed row.
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source) VALUES(10,'4 \u00b5F','4 \u03bcf','source')")
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at,client_id,confirmed_at) VALUES(11,'8 \u00b5F','8 \u00b5f','learned',100,200,'c-1',200)")
            // Second question: the learned answer sits on the case-folded row, the bundled one on the correct row.
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at,client_id,confirmed_at) VALUES(20,'8 \u00b5F','8 \u03bcf','learned',300,400,'c-2',400)")
            db.sql("INSERT INTO qa_answers(question_id,answer_text,answer_key,source) VALUES(21,'4 \u00b5F','4 \u00b5f','source')")
            db.sql("INSERT INTO qa_authoritative VALUES('bank.json','Module','${foldedKeyOf(second, options)}','e20',3)")
        }
        val entries = store(installed, oldSchemaFile("bundle.db")).entries("bank.json")!!
        assertEquals(listOf("8 \u00b5F"), entries.single { it.question == first }.answers)
        assertEquals(listOf("8 \u00b5F"), entries.single { it.question == second }.answers)
        BundledSQLiteDriver().open(installed.absolutePath).use { db ->
            // One row per question, under the app's key, with its options and the remote link; nothing left behind.
            assertEquals(1L, db.long("SELECT count(*) FROM qa_questions WHERE question_text='$first' AND question_key='${keyOf(first, options)}' AND compass_entry_id='e10'"))
            assertEquals(1L, db.long("SELECT count(*) FROM qa_questions WHERE question_text='$second' AND question_key='${keyOf(second, options)}' AND compass_entry_id='e20'"))
            assertEquals(0L, db.long("SELECT count(*) FROM qa_questions WHERE id IN (10,20)"))
            assertEquals(0L, db.long("SELECT count(*) FROM qa_options WHERE question_id NOT IN (SELECT id FROM qa_questions)"))
            assertEquals(0L, db.long("SELECT count(*) FROM qa_answers WHERE question_id NOT IN (SELECT id FROM qa_questions)"))
            assertEquals(2L, db.long("SELECT count(*) FROM qa_options WHERE question_id=21"))
            assertEquals(1L, db.long("SELECT count(*) FROM qa_answers WHERE question_id=11 AND client_id='c-1' AND confirmed_at=200 AND answer_key='8 \u00b5f'"))
            assertEquals(1L, db.long("SELECT count(*) FROM qa_answers WHERE question_id=21 AND client_id='c-2' AND confirmed_at=400 AND answer_key='8 \u00b5f' AND source='learned'"))
            // The correction record follows the question to its new key.
            assertEquals(keyOf(second, options), db.text("SELECT question_key FROM qa_authoritative WHERE entry_id='e20'"))
            assertEquals(1L, db.long("SELECT count(*) FROM qa_authoritative"))
        }
        assertTrue(File(root, "seagull.db.before-v4.bak").isFile)
    }

    /** The key the old bundle builder stored: Python's casefold() turned the micro sign into Greek mu. */
    private fun foldedKeyOf(question: String, options: List<String>): String {
        fun fold(value: String) = value.replace('\u00b5', '\u03bc')
        val payload = fold(QuestionAnswerText.normalize(question)) + "\u001f" + options.map { fold(QuestionAnswerText.normalizedOption(it)) }.sorted().joinToString("\u001e")
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** A copy of [bundle] with different contents, so the next open merges it again. */
    private fun changedBundle(bundle: File, change: (SQLiteConnection) -> Unit = {}): File =
        File(root, "bundle-${System.nanoTime()}.db").also { updated ->
            bundle.copyTo(updated)
            BundledSQLiteDriver().open(updated.absolutePath).use { db -> db.sql("INSERT OR REPLACE INTO metadata VALUES('build','${updated.name}')"); change(db) }
        }

    /** A remote entry carrying this file's own keys, as the remote service returns them. */
    private fun remote(id: String, question: String, options: List<String>, answers: List<String>, revision: Long, retired: Boolean = false, bank: String = "bank.json", authoritative: Boolean = false) =
        RemoteQuestionAnswer(id, bank, "Module", question, keyOf(question, options), options, answers.map { KeyedAnswer(it, QuestionAnswerText.normalize(it)) }, revision, retired, authoritative)

    private fun keyOf(question: String, options: List<String>): String {
        val payload = QuestionAnswerText.normalize(question) + "\u001f" + options.map(QuestionAnswerText::normalizedOption).sorted().joinToString("\u001e")
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun SQLiteConnection.sql(value: String) = prepare(value).use { it.step() }
    private fun SQLiteConnection.long(value: String) = prepare(value).use { it.step(); it.getLong(0) }
    private fun SQLiteConnection.text(value: String) = prepare(value).use { it.step(); it.getText(0) }
}
