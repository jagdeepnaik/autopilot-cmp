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
                sql("CREATE TABLE qa_questions(id INTEGER PRIMARY KEY,bank_name TEXT NOT NULL,module TEXT NOT NULL,question_text TEXT NOT NULL,question_key TEXT NOT NULL,UNIQUE(bank_name,module,question_key))")
                sql("CREATE TABLE qa_options(question_id INTEGER NOT NULL,position INTEGER NOT NULL,option_text TEXT NOT NULL,PRIMARY KEY(question_id,position))")
                sql("CREATE TABLE qa_answers(question_id INTEGER NOT NULL,answer_text TEXT NOT NULL,answer_key TEXT NOT NULL,source TEXT NOT NULL,learned_at INTEGER,reviewed_at INTEGER,PRIMARY KEY(question_id,answer_key))")
                sql("INSERT INTO qa_questions VALUES(1,'bank.json','Module','Known?','key')")
                sql("INSERT INTO qa_options VALUES(1,1,'A')")
                sql("INSERT INTO qa_options VALUES(1,2,'B')")
                sql("INSERT INTO qa_answers VALUES(1,'B','b','source',NULL,NULL)")
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
