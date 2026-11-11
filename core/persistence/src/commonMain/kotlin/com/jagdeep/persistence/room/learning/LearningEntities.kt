package com.jagdeep.persistence.room.learning

import androidx.room.*

@Entity(tableName = "learning_accounts")
internal data class LearningAccountEntity(@PrimaryKey val key: String, val displayName: String, val checkedAt: Long)
@Entity(tableName = "learning_records", primaryKeys = ["account", "key"], indices = [Index("account")])
internal data class LearningRecordEntity(val account: String, val key: String, val course: String, val kind: String,
    val title: String, val status: String, val score: Double?, val details: String, val observedAt: Long)
