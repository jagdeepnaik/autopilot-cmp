package com.jagdeep.home.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val OCEANTG_ANSWER_BANK = "oceantg-ver-1.0.json"

/** Ocean Learning Platform routine. It deliberately stops before the final assessment. */
@Serializable
@SerialName("oceanTG")
data class OceanTGRoutine(
    override val id: String,
    override val name: String,
    val waitAfterClickMillis: Long = 2_000,
    val answerPracticeQuestions: Boolean = true,
    val answerBankFile: String = OCEANTG_ANSWER_BANK,
    val courseCodes: List<String> = emptyList(),
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.OceanTG
}

@Serializable
internal data class OceanChapter(val id: String, val title: String, val status: String)

@Serializable
internal data class OceanPracticeOption(val id: String, val text: String, val selected: Boolean = false)

@Serializable
internal data class OceanPracticeQuestion(
    val text: String,
    val options: List<OceanPracticeOption>,
    val feedback: String? = null,
    val correctAnswer: String? = null,
)

/** A small semantic view of the Ocean page; no page HTML crosses the local bridge. */
@Serializable
internal data class OceanReading(
    val type: String = "oceanReading",
    val session: String,
    val document: String,
    val top: Boolean,
    val courseTitle: String = "",
    val courseCode: String? = null,
    val profileName: String? = null,
    val path: String = "",
    val results: List<OceanChapter> = emptyList(),
    val chapters: List<OceanChapter> = emptyList(),
    val chapterTitle: String? = null,
    val slide: Int? = null,
    val slideCount: Int? = null,
    val media: String = "none",
    val finished: Boolean = false,
    val practice: OceanPracticeQuestion? = null,
)

@Serializable
internal data class OceanAction(val request: Long, val document: String, val kind: String, val target: String? = null)

@Serializable
internal data class OceanActionAck(val type: String, val session: String, val request: Long, val success: Boolean)

@Serializable
internal data class OceanCommand(val session: String, val action: OceanAction? = null)

data class OceanProgress(val detail: String = "Waiting for Ocean Learning Platform", val chapter: String? = null, val slide: Int? = null, val slideCount: Int? = null, val completed: Boolean = false)

/** Makes decisions only from the semantic reading sent by the page helper. */
internal class OceanTGSession(val session: String, private val answerPracticeQuestions: Boolean, private val courseCodes: List<String>) {
    private var request = 0L
    private var pending: OceanAction? = null
    private var answers: CourseAnswerBank? = null
    private var profile: com.jagdeep.persistence.room.learning.LearningAccount? = null
    private var currentCode: String? = null
    private var currentCourse: String = ""
    private val savedRecordKeys = mutableSetOf<String>()
    internal val pendingRecords = linkedMapOf<String, OceanCompletion>()
    var progress = OceanProgress(); private set
    val action: OceanAction? get() = pending

    fun configureAnswers(bank: CourseAnswerBank?) { answers = bank }
    fun configureProfile(name: String?, saved: Collection<String>) {
        val value = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        profile = com.jagdeep.persistence.room.learning.LearningAccount("olp:" + CourseAnswerBank.normalize(value), value, 0)
        savedRecordKeys += saved
    }
    internal val account get() = profile
    fun acknowledge(request: Long, success: Boolean) { if (pending?.request == request && success) pending = null }

    fun read(reading: OceanReading) {
        if (reading.session != session) return
        val practice = reading.practice
        if (practice != null) {
            val answer = answers?.answer(reading.chapterTitle.orEmpty(), CourseQuestion(practice.text, practice.text, practice.options.map { CourseOption(it.id, it.text) }))
            progress = OceanProgress(if (answer == null) "Practice question needs an answer in the answer bank." else "Answering practice question", reading.chapterTitle, reading.slide, reading.slideCount)
            if (answer != null && answer.id != practice.options.firstOrNull { it.selected }?.id && answerPracticeQuestions) issue(reading, "answerPractice", answer.id)
            return
        }
        if (reading.top) {
            configureProfile(reading.profileName, emptySet())
            val currentCode = courseCodes.firstOrNull { it == reading.courseCode }
            if (reading.path.startsWith("/OLP/search")) {
                val expected = courseCodes.firstOrNull { it !in completedCodes }
                val found = reading.results.firstOrNull { it.id == expected }
                progress = if (found == null) OceanProgress("No OLP activity was found for code $expected.") else OceanProgress("Opening OLP course $expected")
                if (found != null) issue(reading, "openCourse", found.title)
                return
            }
            if (currentCode == null && courseCodes.isNotEmpty()) {
                val expected = courseCodes.firstOrNull { it !in completedCodes } ?: return
                progress = OceanProgress("Searching OLP for course code $expected")
                issue(reading, "searchCourse", expected)
                return
            }
            this.currentCode = currentCode
            currentCourse = reading.courseTitle
            reading.chapters.filter { it.title.trim().lowercase() != "assessment" && it.status.contains("completed", true) }.forEach { saveChapter(it.title) }
            val next = reading.chapters.firstOrNull { chapter ->
                chapter.title.trim().lowercase() != "assessment" &&
                    !chapter.status.contains("completed", true) &&
                    "olp:${currentCode}:chapter:${CourseAnswerBank.normalize(chapter.title)}" !in savedRecordKeys
            }
            if (next == null) {
                currentCode?.let(completedCodes::add)
                val nextCode = courseCodes.firstOrNull { it !in completedCodes }
                progress = if (nextCode == null) OceanProgress("Learning chapters complete. Assessments are left for you to start manually.", completed = true) else OceanProgress("Searching OLP for course code $nextCode")
                if (nextCode != null) issue(reading, "searchCourse", nextCode)
            } else progress = OceanProgress("Opening ${next.title}", next.title)
            if (next != null) issue(reading, "openChapter", next.id)
            return
        }
        progress = OceanProgress(
            when { reading.finished -> "Finishing ${reading.chapterTitle ?: "chapter"}"; reading.media == "playing" -> "Watching ${reading.chapterTitle ?: "chapter"}"; else -> "Continuing ${reading.chapterTitle ?: "chapter"}" },
            reading.chapterTitle, reading.slide, reading.slideCount,
        )
        when {
            reading.finished -> { reading.chapterTitle?.let(::saveChapter); issue(reading, "returnToMenu") }
            reading.media == "playing" -> Unit
            else -> issue(reading, "next")
        }
    }

    private val completedCodes = mutableSetOf<String>()

    private fun saveChapter(chapter: String) {
        val account = profile ?: return
        val code = currentCode ?: return
        val key = "olp:$code:chapter:${CourseAnswerBank.normalize(chapter)}"
        if (!savedRecordKeys.add(key)) return
        pendingRecords[key] = OceanCompletion(key, "$code · $currentCourse", chapter)
    }

    private fun issue(reading: OceanReading, kind: String, target: String? = null) {
        val old = pending
        if (old != null && old.document == reading.document && old.kind == kind && old.target == target) return
        pending = OceanAction(++request, reading.document, kind, target)
    }
}

internal data class OceanCompletion(val key: String, val course: String, val chapter: String)
