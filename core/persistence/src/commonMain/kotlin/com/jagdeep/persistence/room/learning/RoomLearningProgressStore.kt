package com.jagdeep.persistence.room.learning

import com.jagdeep.persistence.room.AppRoomDatabase
import kotlinx.coroutines.flow.map

class RoomLearningProgressStore(private val database: AppRoomDatabase) : LearningProgressStore {
    private val dao = database.getLearningDao()
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
