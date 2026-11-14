package com.jagdeep.home.data

import com.jagdeep.home.domain.*
import com.jagdeep.shared.answers.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DirectoryCourseAnswerBanksTest {
    private val content = """{"Module":[{"question":"Question?","options":["A","B"],"answer":"A"}]}"""
    @Test fun `old saved default names resolve to the versioned bank without changing old files`() = runTest {
        val root=Files.createTempDirectory("versioned-banks").toFile()
        try {
            root.resolve("sagarmeinyog.json").writeText("old data preserved")
            val banks=DirectoryCourseAnswerBanks(root,bundled=mapOf(DEFAULT_ANSWER_BANK_FILE to {content}))
            assertNotNull(banks.forName("sagarmeinyog.json").load())
            assertEquals("old data preserved",root.resolve("sagarmeinyog.json").readText())
            assertEquals(content,root.resolve(DEFAULT_ANSWER_BANK_FILE).readText())
            assertEquals(listOf(DEFAULT_ANSWER_BANK_FILE),banks.names())
            assertEquals(DEFAULT_DGS_ANSWER_BANK_FILE,resolvedAnswerBankFileName("dgs-elearning.json"))
            assertEquals("other-ver-2.0.json",resolvedAnswerBankFileName("other-ver-2.0"))
        } finally { root.deleteRecursively() }
    }
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
    @Test fun `given a bundled bank when the folder has none then it is copied and later user edits are kept`() = runTest {
        val root=Files.createTempDirectory("bundled-banks").toFile()
        try {
            val folder=root.resolve("banks")
            val banks=DirectoryCourseAnswerBanks(folder,bundled=mapOf(DEFAULT_ANSWER_BANK_FILE to {content},"extra.json" to {content}))
            assertEquals(listOf("extra.json",DEFAULT_ANSWER_BANK_FILE),banks.names())
            assertEquals(content,folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText())
            val edited=content.replace("\"answer\":\"A\"","\"answer\":\"B\"")
            folder.resolve(DEFAULT_ANSWER_BANK_FILE).writeText(edited)
            assertNotNull(banks.load())
            assertEquals(edited,folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText())
        } finally {root.deleteRecursively()}
    }
    @Test fun `given a larger bundled bank then it merges new questions without losing local questions`() = runTest {
        val root=Files.createTempDirectory("bundled-bank-update").toFile()
        try {
            val folder=root.resolve("banks")
            val local="""{"Module":[{"question":"Local question?","options":["A","B"],"answer":"B"}]}"""
            val bundled="""{"Module":[{"question":"Bundled question?","options":["A","B"],"answer":"A"},{"question":"Another bundled question?","options":["A","B"],"answer":"B"}]}"""
            folder.mkdirs()
            folder.resolve(DEFAULT_ANSWER_BANK_FILE).writeText(local)
            val banks=DirectoryCourseAnswerBanks(folder,bundled=mapOf(DEFAULT_ANSWER_BANK_FILE to {bundled}))
            val merged=assertNotNull(banks.load())
            assertEquals(3,merged.questionCount)
            assertTrue(folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText().contains("Local question?"))
            assertTrue(folder.resolve(DEFAULT_ANSWER_BANK_FILE).readText().contains("Bundled question?"))
        } finally {root.deleteRecursively()}
    }
    @Test fun `given an invalid or unsafe bundled bank when preparing then nothing is written`() = runTest {
        val root=Files.createTempDirectory("bundled-banks").toFile()
        try {
            val folder=root.resolve("banks")
            val banks=DirectoryCourseAnswerBanks(folder,bundled=mapOf(DEFAULT_ANSWER_BANK_FILE to {"invalid"},"../outside.json" to {content},"missing.json" to {null}))
            assertEquals(emptyList(),banks.names())
            assertFalse(root.resolve("outside.json").exists())
            assertEquals(emptyList(),folder.list()!!.toList())
        } finally {root.deleteRecursively()}
    }
}
