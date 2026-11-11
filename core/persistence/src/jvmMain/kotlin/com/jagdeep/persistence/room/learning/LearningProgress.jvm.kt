package com.jagdeep.persistence.room.learning

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jagdeep.persistence.room.AppRoomDatabase
import com.jagdeep.persistence.room.createAppRoomDatabase
import com.jagdeep.persistence.room.getRoomDatabaseBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One AppRoomDatabase; old progress is imported before any consumer can write new data. */
fun openLearningProgressStore(path: String, previousProgressPath: String? = null): LearningProgressStore {
    File(path).absoluteFile.parentFile.mkdirs()
    val database = createAppRoomDatabase(getRoomDatabaseBuilder(path))
    return ImportedLearningStore(database, previousProgressPath?.takeIf { File(it).canonicalPath != File(path).canonicalPath })
}

private class ImportedLearningStore(private val database: AppRoomDatabase, private val previous: String?) : LearningProgressStore {
    private val delegate = RoomLearningProgressStore(database)
    private val lock = Mutex()
    private var imported = false
    private suspend fun prepare() = lock.withLock {
        if (imported) return@withLock
        withContext(Dispatchers.IO) {
            if (previous != null && File(previous).isFile && database.getLearningDao().accountCount() == 0) {
                // Snapshot the old WAL-backed database consistently. No writes, deletes, or schema changes.
                val connection = BundledSQLiteDriver().open(previous)
                try {
                    connection.prepare("PRAGMA query_only=ON").use { it.step() }
                    connection.prepare("BEGIN").use { it.step() }
                    val accounts = mutableListOf<LearningAccountEntity>()
                    val records = mutableListOf<LearningRecordEntity>()
                    connection.prepare("SELECT `key`,displayName,checkedAt FROM learning_accounts").use { row ->
                        while (row.step()) accounts.add(LearningAccountEntity(row.getText(0), row.getText(1), row.getLong(2)))
                    }
                    connection.prepare("SELECT account,`key`,course,kind,title,status,score,details,observedAt FROM learning_records").use { row ->
                        while (row.step()) records.add(LearningRecordEntity(row.getText(0), row.getText(1), row.getText(2), row.getText(3),
                            row.getText(4), row.getText(5), if (row.isNull(6)) null else row.getDouble(6), row.getText(7), row.getLong(8)))
                    }
                    connection.prepare("COMMIT").use { it.step() }
                    require(accounts.map { it.key }.toSet().containsAll(records.map { it.account })) { "Progress import has an unknown account" }
                    database.getLearningDao().importIfEmpty(accounts, records)
                } finally { connection.close() }
            }
        }
        imported = true
    }
    override fun accounts() = flow { prepare(); emitAll(delegate.accounts()) }
    override suspend fun records(account: String): List<LearningRecord> { prepare(); return delegate.records(account) }
    override suspend fun save(account: LearningAccount, records: List<LearningRecord>) { prepare(); delegate.save(account, records) }
    override fun close() = delegate.close()
}
