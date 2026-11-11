package com.jagdeep.persistence.room.learning

import kotlinx.coroutines.flow.Flow

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

