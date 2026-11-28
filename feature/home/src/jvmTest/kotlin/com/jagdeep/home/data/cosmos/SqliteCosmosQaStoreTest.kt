package com.jagdeep.home.data.cosmos

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.ReviewedAnswer
import com.jagdeep.home.domain.cosmos.CosmosAnswer
import com.jagdeep.home.domain.cosmos.CosmosEntry
import com.jagdeep.home.domain.cosmos.CosmosOption
import com.jagdeep.persistence.qa.QuestionAnswerText
import com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class SqliteCosmosQaStoreTest {
    private val root = createTempDirectory("autopilot-cosmos-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    /** An empty routine file in the shipped schema (before sync). */
    private fun emptyBundle(): File = File(root, "bundle.db").also { file ->
        if (file.exists()) return@also
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            fun sql(value: String) = db.prepare(value).use { it.step() }
            sql("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            sql("CREATE TABLE qa_questions (id INTEGER PRIMARY KEY, bank_name TEXT NOT NULL, module TEXT NOT NULL, question_text TEXT NOT NULL, question_key TEXT NOT NULL, UNIQUE(bank_name, module, question_key))")
            sql("CREATE TABLE qa_options (question_id INTEGER NOT NULL, position INTEGER NOT NULL, option_text TEXT NOT NULL, PRIMARY KEY(question_id, position))")
            sql("CREATE TABLE qa_answers (question_id INTEGER NOT NULL, answer_text TEXT NOT NULL, answer_key TEXT NOT NULL, source TEXT NOT NULL CHECK(source IN ('source','learned')), learned_at INTEGER, reviewed_at INTEGER, PRIMARY KEY(question_id, answer_key))")
        }
    }

    private fun banks() = SqliteCourseAnswerBanks(File(root, "qa"), { emptyBundle().inputStream() }, File(root, "legacy"))

    private val options = listOf("Two", "Three")

    private fun entry(id: String, revision: Long, answer: String = "Three", status: String = "approved", bankName: String = DEFAULT_ANSWER_BANK_FILE) = CosmosEntry(
        id, "smy", bankName, "Module 3", "What is the minimum?", QuestionAnswerText.questionKey("What is the minimum?", options),
        listOf(CosmosOption(2, "Three"), CosmosOption(1, "Two")),
        listOf(CosmosAnswer(answer, QuestionAnswerText.answerKey(answer))), "bundled", status, revision,
    )

    @Test fun given_downloaded_entries_when_a_routine_loads_its_bank_then_the_approved_answer_is_an_exact_match() = runBlocking {
        val banks = banks()
        val store = SqliteCosmosQaStore(banks)
        assertEquals(0L, store.lastRevision("smy"))
        assertTrue(store.apply("smy", listOf(entry("e1", 7)), 7))
        assertEquals(7L, store.lastRevision("smy"))
        val bank = assertNotNull(banks.forName(DEFAULT_ANSWER_BANK_FILE).load())
        val saved = bank.module("Module 3")!!.single()
        assertEquals("Three", saved.answer)
        assertEquals(listOf("Two", "Three"), saved.options)

        assertTrue(store.apply("smy", listOf(entry("e1", 9, status = "retired")), 9))
        assertEquals(null, banks.forName(DEFAULT_ANSWER_BANK_FILE).load())

        // Entries are stored under the bank name they carry, not a guessed one.
        assertTrue(store.apply("smy", listOf(entry("e2", 10, bankName = "sagarmeinyog-extra.json")), 10))
        assertEquals("Three", banks.forName("sagarmeinyog-extra.json").load()!!.module("Module 3")!!.single().answer)
    }

    @Test fun given_a_confirmed_answer_when_listed_for_upload_then_it_carries_the_file_keys_and_signals_sync() = runBlocking {
        val banks = banks()
        val store = SqliteCosmosQaStore(banks, now = { 5_000 })
        val signal = async { banks.reviewedSaves.first() }
        yield()
        // Answers saved from quiz feedback are reviewed but not confirmed by the user: never uploaded, no signal.
        banks.forName(DEFAULT_ANSWER_BANK_FILE).saveReviewed("Module 1", listOf(ReviewedAnswer(CourseAnswer("Quiz?", listOf("A", "B"), "A"), null)))
        banks.forName(DEFAULT_ANSWER_BANK_FILE).saveConfirmed("Module 1", listOf(ReviewedAnswer(CourseAnswer("  What   IS it? ", listOf("1. Alpha", "2. Beta"), "2. Beta"), null)))
        signal.await()
        // References collected without review are never uploaded.
        banks.forName(DEFAULT_ANSWER_BANK_FILE).saveReference("Module 1", CourseAnswer("Ref?", listOf("X", "Reference answer"), "X"))
        val pending = store.pending("smy", 100)!!.single()
        assertEquals(DEFAULT_ANSWER_BANK_FILE, pending.bankName)
        assertEquals(QuestionAnswerText.questionKey("  What   IS it? ", listOf("1. Alpha", "2. Beta")), pending.questionKey)
        assertEquals(listOf(1, 2), pending.options.map { it.position })
        assertEquals("2. beta", pending.answer.answerKey)
        assertTrue(pending.fitsContract())
        // Keys as the contract states them: 64-hex question key, normalised-text answer key.
        assertTrue(pending.keysFitContract())
        assertNotNull(pending.reviewedAt)
        assertEquals(1, store.pendingCount())
        assertTrue(store.markSubmitted("smy", mapOf(pending.clientId to "pending")))
        assertEquals(0, store.pendingCount())
    }
}
