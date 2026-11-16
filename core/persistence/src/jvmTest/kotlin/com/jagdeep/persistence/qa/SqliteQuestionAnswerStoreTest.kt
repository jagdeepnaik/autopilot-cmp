package com.jagdeep.persistence.qa

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SqliteQuestionAnswerStoreTest {
    @Test fun given_bundled_database_when_reading_and_learning_then_answers_are_persisted_with_learned_source() = runBlocking {
        val root = createTempDirectory("autopilot-qa-").toFile()
        try {
            val source = File(root, "source.db")
            BundledSQLiteDriver().open(source.absolutePath).use { db ->
                fun sql(value: String) = db.prepare(value).use { it.step() }
                sql("CREATE TABLE qa_questions(id INTEGER PRIMARY KEY,question_text TEXT NOT NULL,question_normalized TEXT NOT NULL)")
                sql("CREATE TABLE qa_options(question_id INTEGER NOT NULL,position INTEGER NOT NULL,option_text TEXT NOT NULL,option_normalized TEXT NOT NULL,PRIMARY KEY(question_id,position))")
                sql("CREATE TABLE qa_question_sources(id INTEGER PRIMARY KEY,question_id INTEGER NOT NULL,routine TEXT NOT NULL,bank_name TEXT NOT NULL,module TEXT NOT NULL,source TEXT NOT NULL,source_reference TEXT,imported_at TEXT NOT NULL)")
                sql("CREATE TABLE qa_source_answers(source_id INTEGER NOT NULL,answer_text TEXT NOT NULL,answer_normalized TEXT NOT NULL,learned_at INTEGER,reviewed_at INTEGER,PRIMARY KEY(source_id,answer_normalized))")
                sql("INSERT INTO qa_questions VALUES(1,'Known?','known?')")
                sql("INSERT INTO qa_options VALUES(1,1,'A','a')")
                sql("INSERT INTO qa_options VALUES(1,2,'B','b')")
                sql("INSERT INTO qa_question_sources VALUES(1,1,'routine','bank.json','Module','bundled_json','source',CURRENT_TIMESTAMP)")
                sql("INSERT INTO qa_source_answers VALUES(1,'B','b',NULL,NULL)")
            }
            val store = SqliteQuestionAnswerStore(File(root, "installed.db")) { source.inputStream() }
            assertEquals(listOf("B"), store.entries("bank.json")!!.single().answers)
            assertEquals(QuestionAnswerWriteResult.Saved, store.save("bank.json", listOf(LearnedQuestionAnswer("Module", "Learned?", listOf("One", "Two"), "Two")), reviewed = false))
            val learned = store.entries("bank.json")!!.single { it.question == "Learned?" }
            assertEquals(listOf("Two"), learned.answers)
            assertEquals("learned", learned.source)
        } finally { root.deleteRecursively() }
    }
}
