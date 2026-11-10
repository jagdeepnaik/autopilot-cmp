package com.jagdeep.home.data

import com.jagdeep.home.domain.*
import com.jagdeep.shared.answers.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DirectoryCourseAnswerBanksTest {
    private val content = """{"Module":[{"question":"Question?","options":["A","B"],"answer":"A"}]}"""
    @Test fun `migrate legacy bank once and never replace a permanent user update`() = runTest {
        val root=Files.createTempDirectory("named-banks").toFile()
        try {
            val legacy=root.resolve("legacy.json").apply {writeText(content)}
            val folder=root.resolve("banks")
            val banks=DirectoryCourseAnswerBanks(folder,listOf(legacy))
            assertEquals(listOf(DEFAULT_ANSWER_BANK_FILE),banks.names())
            assertEquals(content,folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText())
            folder.resolve(DEFAULT_ANSWER_BANK_FILE).writeText(content.replace("\"answer\":\"A\"","\"answer\":\"B\""))
            assertNotNull(banks.load())
            assertEquals(content,legacy.readText())
            assertTrue(folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText().contains("\"answer\":\"B\""))
        } finally {root.deleteRecursively()}
    }
    @Test fun `import named bank validate it and save approved answers only to that file`() = runTest {
        val root=Files.createTempDirectory("named-banks").toFile()
        try {
            val source=root.resolve("source.json").apply {writeText(content)}
            val folder=root.resolve("banks")
            val banks=DirectoryCourseAnswerBanks(folder,chooseFile={source})
            assertTrue(banks.importFile("my-bank")!!.startsWith("Imported"))
            assertTrue(banks.validate("my-bank.json").contains("is valid"))
            assertNotNull(banks.forName("my-bank.json").load())
            assertTrue(banks.forName("my-bank.json").save("Module",CourseAnswer("Another?",listOf("A","B"),"B")) is AnswerBankSaveResult.Saved)
            assertTrue(folder.resolve("my-bank.json").readText().contains("Another?"))
            assertFalse(folder.resolve(DEFAULT_ANSWER_BANK_FILE).exists())
            val saved=folder.resolve("my-bank.json").readText()
            assertTrue(banks.importFile("my-bank.json")!!.contains("already exists"))
            assertEquals(saved,folder.resolve("my-bank.json").readText())
            source.writeText("invalid")
            assertTrue(banks.importFile("bad.json")!!.contains("not a valid"))
            assertFalse(folder.resolve("bad.json").exists())
            assertNull(banks.forName("../outside.json").load())
            assertNull(answerBankFileName("../outside.json"))
        } finally {root.deleteRecursively()}
    }
}
