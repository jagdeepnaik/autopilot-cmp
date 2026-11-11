package com.jagdeep.persistence.room.learning

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlin.test.*

class LearningProgressTest {
    @Test fun `given two accounts then transactional imports remain isolated durable and idempotent`() = runBlocking {
        val folder = Files.createTempDirectory("learning-db").toFile()
        val path = folder.resolve("history.db").absolutePath
        var store = openLearningProgressStore(path)
        try {
            val a = LearningAccount("a", "Same name", 20)
            val b = LearningAccount("b", "Same name", 30)
            val row = LearningRecord("a", "attempt1", "Module", "assessment", "Test", "passed", 90.0, observedAt = 20)
            store.save(a,listOf(row)); store.save(a,listOf(row)); store.save(b,listOf(row.copy(account = "b",status = "failed",score = 70.0)))
            store.save(a,listOf(row.copy(status="unsubmitted",score=null)))
            assertEquals(1,store.records("a").size)
            assertEquals("passed",store.records("a").single().status)
            assertEquals("failed",store.records("b").single().status)
            assertEquals(2,store.accounts().first().size)
            assertFailsWith<IllegalArgumentException> { store.save(a,listOf(row.copy(key = "new"),row.copy(account = "b"))) }
            assertEquals(1,store.records("a").size)
            store.save(a.copy(checkedAt = 40), emptyList())
            assertEquals(1,store.records("a").size,"Empty refresh cannot erase history")
            store.close(); store = openLearningProgressStore(path)
            assertEquals("passed",store.records("a").single().status)
            androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path).use { db ->
                val tables=mutableSetOf<String>()
                db.prepare("SELECT name FROM sqlite_master WHERE type='table'").use { row -> while(row.step()) tables.add(row.getText(0)) }
                assertTrue(tables.containsAll(setOf("app_usage","learning_accounts","learning_records")))
                assertFalse(tables.any { it.contains("transfer",ignoreCase=true) })
            }
        } finally { store.close(); folder.deleteRecursively() }
    }
}
