package com.jagdeep.persistence.room.learning

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlin.test.*

class LearningImportTest {
    @Test fun `existing per-user progress is imported once into AppRoomDatabase and source stays intact`() = runBlocking {
        val folder=Files.createTempDirectory("progress-import").toFile()
        val source=folder.resolve("course-progress.db")
        val target=folder.resolve("autopilot.db")
        fun sql(text:String) { BundledSQLiteDriver().open(source.absolutePath).use { db -> db.prepare(text).use { it.step() } } }
        try {
            sql("CREATE TABLE learning_accounts (`key` TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, checkedAt INTEGER NOT NULL)")
            sql("CREATE TABLE learning_records (account TEXT NOT NULL, `key` TEXT NOT NULL, course TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, score REAL, details TEXT NOT NULL, observedAt INTEGER NOT NULL, PRIMARY KEY(account,`key`))")
            sql("INSERT INTO learning_accounts VALUES ('a','Person A',100),('b','Person B',200)")
            sql("INSERT INTO learning_records VALUES ('a','result','Course','assessment','Test','passed',90,'',100),('b','video','Course','lesson','Video','completed',NULL,'/lesson',200)")
            sql("INSERT INTO learning_records VALUES ('b','study','DGS','dgs-study','Course','in-progress',NULL,'123000',200)")
            val original=source.readBytes()
            var store=openLearningProgressStore(target.absolutePath,source.absolutePath)
            assertEquals(2,store.accounts().first().size)
            assertEquals("passed",store.records("a").single().status)
            assertEquals("completed",store.records("b").single { it.key=="video" }.status)
            assertEquals("123000",store.records("b").single { it.kind=="dgs-study" }.details)
            store.save(LearningAccount("a","Person A",300),listOf(store.records("a").single().copy(details="newer")))
            store.close()
            assertContentEquals(original,source.readBytes())
            store=openLearningProgressStore(target.absolutePath,source.absolutePath)
            assertEquals("newer",store.records("a").single().details,"Import must not overwrite newer progress")
            assertEquals(2,store.records("b").size)
            store.close()
        } finally { folder.deleteRecursively() }
    }

    @Test fun `invalid import rolls back without deleting the source or partially copying accounts`() = runBlocking {
        val folder=Files.createTempDirectory("invalid-import").toFile()
        val source=folder.resolve("old.db")
        val target=folder.resolve("app.db")
        try {
            BundledSQLiteDriver().open(source.absolutePath).use { db ->
                db.prepare("CREATE TABLE learning_accounts (`key` TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, checkedAt INTEGER NOT NULL)").use { it.step() }
                db.prepare("INSERT INTO learning_accounts VALUES ('a','Person',100)").use { it.step() }
            }
            val store=openLearningProgressStore(target.absolutePath,source.absolutePath)
            try { assertFails { store.accounts().first() } } finally { store.close() }
            assertTrue(source.isFile)
            val clean=openLearningProgressStore(target.absolutePath)
            try { assertEquals(0,clean.accounts().first().size) } finally { clean.close() }
        } finally { folder.deleteRecursively() }
    }
}
