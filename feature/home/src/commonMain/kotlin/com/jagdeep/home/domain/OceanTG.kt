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
internal class OceanTGSession(val session: String, private val answerPracticeQuestions: Boolean) {
    private var request = 0L
    private var pending: OceanAction? = null
    private var answers: CourseAnswerBank? = null
    var progress = OceanProgress(); private set
    val action: OceanAction? get() = pending

    fun configureAnswers(bank: CourseAnswerBank?) { answers = bank }
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
            val next = reading.chapters.firstOrNull { it.title.trim().lowercase() != "assessment" && !it.status.contains("completed", true) }
            progress = if (next == null) OceanProgress("Learning chapters complete. Assessment is left for you to start manually.", completed = true) else OceanProgress("Opening ${next.title}", next.title)
            if (next != null) issue(reading, "openChapter", next.id)
            return
        }
        progress = OceanProgress(
            when { reading.finished -> "Finishing ${reading.chapterTitle ?: "chapter"}"; reading.media == "playing" -> "Watching ${reading.chapterTitle ?: "chapter"}"; else -> "Continuing ${reading.chapterTitle ?: "chapter"}" },
            reading.chapterTitle, reading.slide, reading.slideCount,
        )
        when {
            reading.finished -> issue(reading, "returnToMenu")
            reading.media == "playing" -> Unit
            else -> issue(reading, "next")
        }
    }

    private fun issue(reading: OceanReading, kind: String, target: String? = null) {
        val old = pending
        if (old != null && old.document == reading.document && old.kind == kind && old.target == target) return
        pending = OceanAction(++request, reading.document, kind, target)
    }
}
