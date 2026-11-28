package com.jagdeep.autopilot.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jagdeep.persistence.qa.QuestionAnswerText
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every key in the packaged routine Q&A files is the key the app computes at run time
 * ([QuestionAnswerText]), so an exact lookup always finds the bundled row. The files are built by
 * `private/scripts/build_qa_database.py`, which must reproduce these text rules exactly.
 */
class BundledQaKeysTest {
    private val root = createTempDirectory("autopilot-bundled-qa-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    @Test fun `every bundled question key and answer key matches the app's text rules`() {
        var questions = 0
        listOf("smy.db", "olp.db", "seagull.db", "marinesamraj.db").forEach { name ->
            // Read a temporary copy: the packaged resource is never opened for writing.
            val copy = File(root, name)
            assertNotNull(javaClass.getResourceAsStream("/qa-routines/$name"), "Missing $name").use { input -> copy.outputStream().use(input::copyTo) }
            BundledSQLiteDriver().open(copy.absolutePath).use { db ->
                val questionMismatches = mutableListOf<String>()
                val answerMismatches = mutableListOf<String>()
                db.prepare("SELECT id,bank_name,module,question_text,question_key FROM qa_questions ORDER BY id").use { row ->
                    while (row.step()) {
                        questions++
                        val id = row.getLong(0)
                        val options = db.prepare("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position").use { option ->
                            option.bindLong(1, id); buildList { while (option.step()) add(option.getText(0)) }
                        }
                        if (row.getText(4) != QuestionAnswerText.questionKey(row.getText(3), options)) {
                            questionMismatches += "$name ${row.getText(1)} / ${row.getText(2)}: ${row.getText(3).take(60)}"
                        }
                        db.prepare("SELECT answer_text,answer_key FROM qa_answers WHERE question_id=?").use { answer ->
                            answer.bindLong(1, id)
                            while (answer.step()) if (answer.getText(1) != QuestionAnswerText.answerKey(answer.getText(0))) {
                                answerMismatches += "$name: ${answer.getText(0).take(60)}"
                            }
                        }
                    }
                }
                assertEquals(emptyList(), questionMismatches, "question_key differs from QuestionAnswerText.questionKey")
                assertEquals(emptyList(), answerMismatches, "answer_key differs from QuestionAnswerText.answerKey")
            }
        }
        assertTrue(questions > 0, "No bundled questions were checked")
    }

    @Test fun `the micro sign stays a micro sign in keys`() {
        assertEquals("c = 4 µf", QuestionAnswerText.normalize("C = 4 µF"))
    }
}
