package com.jagdeep.home.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.MARINESAMRAJ_ANSWER_BANK
import com.jagdeep.persistence.qa.SqliteQuestionAnswerStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MarineSamrajReferenceStoreTest {
    @Test fun `given growing public collection then writes remain durable beyond snapshot size limit`() = runBlocking {
        val root = Files.createTempDirectory("marine-reference-test").toFile()
        try {
            val seed = File(root, "seed.db")
            BundledSQLiteDriver().open(seed.absolutePath).use { db ->
                listOf(
                    "CREATE TABLE qa_questions(id INTEGER PRIMARY KEY,bank_name TEXT NOT NULL,module TEXT NOT NULL,question_text TEXT NOT NULL,question_key TEXT NOT NULL,UNIQUE(bank_name,module,question_key))",
                    "CREATE TABLE qa_options(question_id INTEGER NOT NULL,position INTEGER NOT NULL,option_text TEXT NOT NULL,PRIMARY KEY(question_id,position))",
                    "CREATE TABLE qa_answers(question_id INTEGER NOT NULL,answer_text TEXT NOT NULL,answer_key TEXT NOT NULL,source TEXT NOT NULL,learned_at INTEGER,reviewed_at INTEGER,PRIMARY KEY(question_id,answer_key))",
                ).forEach { sql -> db.prepare(sql).use { it.step() } }
            }
            val folder = File(root, "qa")
            val banks = SqliteCourseAnswerBanks(folder, { seed.inputStream() }, File(root, "legacy"))
            val selected = banks.forName(MARINESAMRAJ_ANSWER_BANK)
            val answer = "Published reference. ".repeat(9000)
            repeat(7) { index -> assertTrue(selected.saveReference("Public references", CourseAnswer("Question $index", listOf(answer, "Reference answer"), answer))) }
            assertNull(selected.load(), "Large reference collection deliberately exceeds the bounded assessment snapshot format")
            val last = CourseAnswer("Final question", listOf("Final reference.", "Reference answer"), "Final reference.")
            assertTrue(selected.saveReference("Public references", last))
            assertTrue(selected.saveReference("Public references", last))
            val reader = SqliteQuestionAnswerStore(File(folder, "marinesamraj.db")) { seed.inputStream() }
            val entries = assertNotNull(reader.entries(MARINESAMRAJ_ANSWER_BANK))
            assertEquals(8, entries.size)
            assertEquals(answer, entries.first().answers.single())
            assertFalse(File(folder, "smy.db").exists(), "References must stay in the MarineSamraj routine database")
        } finally { root.deleteRecursively() }
    }
}
