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
data class ProgressProfileRequest(val token: String, val displayName: String = "",
    val profiles: List<LearningAccount> = emptyList(), val loading: Boolean = false)
data class CourseProgressSummary(val account: String, val displayName: String, val checkedAt: Long, val records: List<LearningRecord>)

/** The fixed catalogue shown by Sagar Mein Yog. Keep the running view useful before a module is opened. */
val SAGAR_MEIN_YOG_MODULES = listOf(
    "Emotional Wellness", "Economic Wellness", "Physical Wellness", "Occupational Wellness", "Social Wellness",
    "Environmental Wellness", "Climatic Wellness", "Intellectual Wellness", "Cultural Wellness", "Spiritual Wellness",
)

enum class CourseModuleAction { Videos, Quizzes, Assessment }
data class CourseModuleStatus(
    val module: String,
    val videosComplete: Boolean,
    val assessmentPassed: Boolean,
    val certificateSaved: Boolean,
    val assessmentScore: Double? = null,
    val automaticAnswers: Int = 0,
    val manualAnswers: Int = 0,
    val videoDurationMillis: Long = 0,
    val assessmentDurationMillis: Long = 0,
    val quizzesComplete: Boolean = false,
    val quizzesCompleted: Int = 0,
    val quizzesTotal: Int = 0,
)

internal fun detailMetric(details: String, name: String): Long =
    Regex("(?:^|\\n)$name=([0-9]+)").find(details)?.groupValues?.get(1)?.toLongOrNull() ?: 0

internal fun assessmentRecords(account: String, rows: List<Map<String,String>>, now: Long): List<LearningRecord>? = runCatching {
    rows.map { row ->
        fun cell(name: String) = row[name]?.trim().orEmpty()
        val course = cell("Course Name").ifBlank { cell("Material Course") }; val title = cell("Test Title"); val type = cell("Test Type")
        val attempt = cell("Attempt Number"); val started = cell("Started At")
        require(course.isNotBlank() && title.isNotBlank() && type.isNotBlank() && attempt.toIntOrNull() != null && started.isNotBlank())
        // The live site decorates older failed attempts as e.g. "NO (passed in another
        // attempt)". It still describes this attempt as failed, and must not stop the
        // entire progress import just because a later attempt passed.
        val passed = cell("Passed").uppercase()
        val status = when {
            passed == "YES" -> "passed"
            passed == "NOT SUBMITTED" -> "unsubmitted"
            passed == "NO" || passed.startsWith("NO (") -> "failed"
            else -> error("Unknown result")
        }
        val score = cell("Percentage").removeSuffix("%").toDoubleOrNull()
        if (status != "unsubmitted") require(score != null && score in 0.0..100.0 && cell("Completed At") !in setOf("", "-"))
        val key = listOf(course,type,title,cell("Material Title"),cell("Topic Name"),attempt,started).joinToString("\u001f")
        LearningRecord(account,key,course,if(type.equals("ASSESSMENT",true) && title.equals("Course Assessment",true)) "assessment" else if(type.equals("QUIZ",true)) "quiz" else "test",title,status,score,
            row.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" },now)
    }
}.getOrNull()
