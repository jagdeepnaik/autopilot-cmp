package com.jagdeep.persistence.room.learning

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class LearningDao {
    @Query("SELECT * FROM learning_accounts ORDER BY displayName") abstract fun accounts(): Flow<List<LearningAccountEntity>>
    @Query("SELECT * FROM learning_records WHERE account = :account ORDER BY observedAt DESC") abstract suspend fun records(account: String): List<LearningRecordEntity>
    @Upsert abstract suspend fun account(value: LearningAccountEntity)
    @Upsert abstract suspend fun rows(value: List<LearningRecordEntity>)
    @Query("SELECT COUNT(*) FROM learning_accounts") abstract suspend fun accountCount(): Int
    @Transaction open suspend fun importIfEmpty(accounts: List<LearningAccountEntity>, values: List<LearningRecordEntity>) {
        if (accountCount() != 0) return
        accounts.forEach { account(it) }
        rows(values)
    }
    @Transaction open suspend fun save(account: LearningAccountEntity, values: List<LearningRecordEntity>) {
        val previous = records(account.key).associateBy { it.key }
        account(account)
        rows(values.filterNot { previous[it.key]?.status == "passed" && it.status != "passed" })
    }
}
