package com.jagdeep.home.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.ReviewedAnswer
import com.jagdeep.home.domain.CourseAnswerBank
import com.jagdeep.home.domain.AnswerBankSaveResult
import com.jagdeep.home.domain.CourseOption
import com.jagdeep.home.domain.CourseQuestion
import kotlinx.serialization.json.Json

class FileCourseAnswerBankStoreTest {
    @Test
    fun `given approved corrections then replace expected answers atomically and reject stale reviews`() = runTest {
        val directory = Files.createTempDirectory("review-answer-bank").toFile()
        try {
            val file = directory.resolve("bank.json")
            val a = CourseAnswer("First?", listOf("A", "B"), "A")
            val b = CourseAnswer("Second?", listOf("A", "B"), "B")
            file.writeText(Json.encodeToString(mapOf("Module" to listOf(a, b))))
            val store = FileCourseAnswerBankStore(listOf(file))
            kotlin.test.assertTrue(store.saveReviewed("Module", listOf(ReviewedAnswer(a.copy(answer = "B"), "A"))) is AnswerBankSaveResult.Saved)
            val saved = file.readText()
            kotlin.test.assertEquals(AnswerBankSaveResult.Conflict, store.saveReviewed("Module", listOf(
                ReviewedAnswer(b.copy(answer = "A"), "B"), ReviewedAnswer(a, "A"))))
            kotlin.test.assertEquals(saved, file.readText(), "A stale batch must not partially save")
            val rows = Json.decodeFromString<Map<String, List<CourseAnswer>>>(saved).getValue("Module")
            kotlin.test.assertEquals("B", rows.single { it.question == "First?" }.answer)
            kotlin.test.assertEquals(b, rows.single { it.question == "Second?" })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `given a confirmed manual answer then save atomically preserve other answers and reject conflicts`() = runTest {
        val directory = Files.createTempDirectory("manual-answer-bank").toFile()
        try {
            val file = directory.resolve("bank.json")
            file.writeText("""{"Module":[{"question":"Known?","options":["A","B"],"answer":"A"},{"question":"Unknown?","options":["A","B"],"answer":null}]}""")
            val store = FileCourseAnswerBankStore(listOf(file))
            val row = CourseAnswer("Unknown?", listOf("B", "A"), "B")
            kotlin.test.assertTrue(store.save("Module", row) is AnswerBankSaveResult.Saved)
            val entries = Json.decodeFromString<Map<String, List<CourseAnswer>>>(file.readText())["Module"]!!
            kotlin.test.assertEquals(2, entries.size)
            kotlin.test.assertEquals("A", entries.single { it.question == "Known?" }.answer)
            kotlin.test.assertEquals("B", entries.single { it.question == "Unknown?" }.answer)
            val saved = file.readText()
            kotlin.test.assertEquals(AnswerBankSaveResult.Conflict, store.save("Module", row.copy(answer = "A")))
            kotlin.test.assertEquals(saved, file.readText())
            kotlin.test.assertEquals(AnswerBankSaveResult.Invalid, store.save("Module", row.copy(answer = "C")))
            kotlin.test.assertEquals(saved, file.readText())
            kotlin.test.assertTrue(store.save("Other", CourseAnswer("New?", listOf("Yes", "No"), "Yes")) is AnswerBankSaveResult.Saved)
            assertNotNull(store.load())
            kotlin.test.assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `given an explicitly supplied real bank then every question and answer round trips through exact matching`() = runTest {
        val path = System.getenv("AUTOPILOT_ANSWER_BANK_TEST") ?: return@runTest
        val file = java.io.File(path)
        val bank = FileCourseAnswerBankStore(listOf(file)).load()
        assertNotNull(bank)
        val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(file.readText())
        for ((title, questions) in modules) for ((index, question) in questions.withIndex()) {
            val options = question.options.mapIndexed { i, text -> CourseOption("$i", text) }
            val actual = bank.answer(title, CourseQuestion("$index", question.question, options))
            fun label(text: String) = CourseAnswerBank.normalize(text).replace(Regex("^\\d+[.)]\\s+"), "")
            if (question.options.map(::label).distinct().size != question.options.size) assertNull(actual, "Ambiguous displayed labels must never be answered")
            else {
                val expected = question.answer?.let { answer -> question.options.single { label(it) == label(answer) } }
                kotlin.test.assertEquals(expected, actual?.text)
            }
        }
    }
    @Test
    fun `given local answer files then valid content loads and invalid or missing files fail closed`() = runTest {
        val directory = Files.createTempDirectory("answer-bank-test").toFile()
        try {
            val file = directory.resolve("sagarmeinyog.json")
            val store = FileCourseAnswerBankStore(listOf(file))
            assertNull(store.load())
            file.writeText("""{"Module":[{"question":"Question?","options":["A","B"],"answer":"B"}]}""")
            assertNotNull(store.load())
            file.writeText("{broken")
            assertNull(store.load())
            file.writeText(" ".repeat(2_000_001))
            assertNull(store.load())
        } finally { directory.deleteRecursively() }
    }
}
