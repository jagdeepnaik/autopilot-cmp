package com.jagdeep.home.data

import com.jagdeep.home.domain.CourseProgressRepository
import com.jagdeep.persistence.room.learning.*

class RoomCourseProgressRepository(private val store: LearningProgressStore) : CourseProgressRepository {
    override val available get() = store.available
    override fun accounts() = store.accounts()
    override suspend fun records(account: String) = store.records(account)
    override suspend fun save(account: LearningAccount, records: List<LearningRecord>) = store.save(account, records)
    override fun close() = store.close()
}
