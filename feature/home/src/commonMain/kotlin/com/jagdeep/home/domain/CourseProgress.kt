package com.jagdeep.home.domain

import com.jagdeep.persistence.room.learning.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

interface CourseProgressRepository {
    val available: Boolean
    fun accounts(): Flow<List<LearningAccount>>
    suspend fun records(account: String): List<LearningRecord>
    suspend fun save(account: LearningAccount, records: List<LearningRecord>)
    fun close() = Unit
}
object NoCourseProgressRepository : CourseProgressRepository {
    override val available = false
    override fun accounts() = flowOf(emptyList<LearningAccount>())
    override suspend fun records(account: String) = emptyList<LearningRecord>()
    override suspend fun save(account: LearningAccount, records: List<LearningRecord>) = error("Progress storage unavailable")
}
@Serializable internal data class ProgressTable(val ready: Boolean = false, val error: String? = null,
    val page: Int = 0, val pages: Int = 0, val total: Int = 0, val rows: List<Map<String,String>> = emptyList(),
    val first: String? = null, val next: String? = null, val unfiltered: Boolean = false,
    val pageSize: Int? = null, val pageSizeControl: String? = null,
    val pageSizeOpen: Boolean = false, val pageSizeOptions: List<ProgressPageSize> = emptyList())
@Serializable internal data class ProgressPageSize(val id: String, val value: Int)
data class ProgressProfileRequest(val token: String, val displayName: String)
data class CourseProgressSummary(val account: String, val displayName: String, val checkedAt: Long, val records: List<LearningRecord>)

internal fun assessmentRecords(account: String, rows: List<Map<String,String>>, now: Long): List<LearningRecord>? = runCatching {
    rows.map { row ->
        fun cell(name: String) = row[name]?.trim().orEmpty()
        val course = cell("Course Name").ifBlank { cell("Material Course") }; val title = cell("Test Title"); val type = cell("Test Type")
        val attempt = cell("Attempt Number"); val started = cell("Started At")
        require(course.isNotBlank() && title.isNotBlank() && type.isNotBlank() && attempt.toIntOrNull() != null && started.isNotBlank())
        val status = when (cell("Passed").uppercase()) { "YES" -> "passed"; "NO" -> "failed"; "NOT SUBMITTED" -> "unsubmitted"; else -> error("Unknown result") }
        val score = cell("Percentage").removeSuffix("%").toDoubleOrNull()
        if (status != "unsubmitted") require(score != null && score in 0.0..100.0 && cell("Completed At") !in setOf("", "-"))
        val key = listOf(course,type,title,cell("Material Title"),cell("Topic Name"),attempt,started).joinToString("\u001f")
        LearningRecord(account,key,course,if(type.equals("ASSESSMENT",true) && title.equals("Course Assessment",true)) "assessment" else if(type.equals("QUIZ",true)) "quiz" else "test",title,status,score,
            row.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" },now)
    }
}.getOrNull()
