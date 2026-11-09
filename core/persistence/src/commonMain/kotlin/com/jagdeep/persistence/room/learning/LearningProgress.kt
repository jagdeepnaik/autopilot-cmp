package com.jagdeep.persistence.room.learning

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Non-sensitive, local learning history. Account keys are explicitly chosen, never credentials. */
data class LearningAccount(val key: String, val displayName: String, val checkedAt: Long)
data class LearningRecord(val account: String, val key: String, val course: String, val kind: String,
    val title: String, val status: String, val score: Double? = null, val details: String = "", val observedAt: Long)
interface LearningProgressStore {
    val available: Boolean get() = true
    fun accounts(): Flow<List<LearningAccount>>
    suspend fun records(account: String): List<LearningRecord>
    /** Upsert only: a partial refresh can never erase history. */
    suspend fun save(account: LearningAccount, records: List<LearningRecord>)
    fun close() = Unit
}

@Entity(tableName = "learning_accounts")
internal data class LearningAccountEntity(@PrimaryKey val key: String, val displayName: String, val checkedAt: Long)
@Entity(tableName = "learning_records", primaryKeys = ["account", "key"], indices = [Index("account")])
internal data class LearningRecordEntity(val account: String, val key: String, val course: String, val kind: String,
    val title: String, val status: String, val score: Double?, val details: String, val observedAt: Long)
@Dao
internal abstract class LearningDao {
    @Query("SELECT * FROM learning_accounts ORDER BY displayName") abstract fun accounts(): Flow<List<LearningAccountEntity>>
    @Query("SELECT * FROM learning_records WHERE account = :account ORDER BY observedAt DESC") abstract suspend fun records(account: String): List<LearningRecordEntity>
    @Upsert abstract suspend fun account(value: LearningAccountEntity)
    @Upsert abstract suspend fun rows(value: List<LearningRecordEntity>)
    @Transaction open suspend fun save(account: LearningAccountEntity, values: List<LearningRecordEntity>) {
        val previous = records(account.key).associateBy { it.key }
        account(account)
        rows(values.filterNot { previous[it.key]?.status == "passed" && it.status != "passed" })
    }
}
@Database(entities = [LearningAccountEntity::class, LearningRecordEntity::class], version = 1)
@ConstructedBy(LearningDatabaseConstructor::class)
abstract class LearningProgressDatabase : RoomDatabase() { internal abstract fun learningDao(): LearningDao }
@Suppress("KotlinNoActualForExpect")
expect object LearningDatabaseConstructor : RoomDatabaseConstructor<LearningProgressDatabase> { override fun initialize(): LearningProgressDatabase }

class RoomLearningProgressStore(private val database: LearningProgressDatabase) : LearningProgressStore {
    private val dao = database.learningDao()
    override fun accounts() = dao.accounts().map { rows -> rows.map { LearningAccount(it.key, it.displayName, it.checkedAt) } }
    override suspend fun records(account: String) = dao.records(account).map { LearningRecord(it.account, it.key, it.course, it.kind, it.title, it.status, it.score, it.details, it.observedAt) }
    override suspend fun save(account: LearningAccount, records: List<LearningRecord>) {
        require(account.key.isNotBlank() && account.key.length <= 240 && account.displayName.isNotBlank() && account.displayName.length <= 200 && account.checkedAt >= 0)
        require(records.size <= 20_000)
        records.forEach { require(it.account == account.key && it.key.isNotBlank() && it.key.length <= 2000 && it.course.length <= 500 && it.title.length <= 1000 && it.details.length <= 20_000 && it.observedAt >= 0 && (it.score == null || it.score.isFinite() && it.score in 0.0..100.0)) }
        dao.save(LearningAccountEntity(account.key, account.displayName, account.checkedAt), records.map { LearningRecordEntity(it.account,it.key,it.course,it.kind,it.title,it.status,it.score,it.details,it.observedAt) })
    }
    override fun close() = database.close()
}
