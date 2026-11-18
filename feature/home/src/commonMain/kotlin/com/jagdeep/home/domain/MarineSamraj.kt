package com.jagdeep.home.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MARINESAMRAJ_ANSWER_BANK = "marinesamraj-public-ver-1.0.json"

/** Public-page collector for MarineSamraj. It never signs in or visits private site areas. */
@Serializable
@SerialName("marineSamraj")
data class MarineSamrajRoutine(
    override val id: String,
    override val name: String,
    val maxPages: Int = 250,
    val answerBankFile: String = MARINESAMRAJ_ANSWER_BANK,
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.MarineSamraj
}

@Serializable
internal data class MarineSamrajReading(
    val type: String = "marineSamrajReading",
    val session: String,
    val document: String,
    val url: String,
    val question: String? = null,
    val answer: String? = null,
    val module: String = "Public questions",
    val links: List<String> = emptyList(),
)

@Serializable
internal data class MarineSamrajAction(val request: Long, val document: String, val url: String)

@Serializable
internal data class MarineSamrajActionAck(val type: String, val session: String, val request: Long, val success: Boolean)

@Serializable
internal data class MarineSamrajCommand(val session: String, val action: MarineSamrajAction? = null)

internal data class MarineSamrajCapture(val module: String, val question: String, val answer: String)

data class MarineSamrajProgress(
    val detail: String = "Open a public MarineSamraj exam-preparation page in Chrome.",
    val pages: Int = 0,
    val questions: Int = 0,
    val completed: Boolean = false,
)

/** Keeps the navigation queue in the app; the page helper only reads public links and executes one URL. */
internal class MarineSamrajSession(val session: String, private val maxPages: Int) {
    private var request = 0L
    private var pending: MarineSamrajAction? = null
    private val queued = ArrayDeque<String>()
    private val visited = linkedSetOf<String>()
    private val captured = mutableSetOf<String>()
    private var pendingCapture: MarineSamrajCapture? = null
    var progress = MarineSamrajProgress(); private set
    val action: MarineSamrajAction? get() = pending

    fun acknowledge(request: Long, success: Boolean) {
        if (pending?.request == request && success) pending = null
        else if (pending?.request == request) progress = progress.copy(detail = "Chrome could not open the next permitted public page.")
    }

    fun takeCapture(): MarineSamrajCapture? = pendingCapture.also { pendingCapture = null }

    fun read(reading: MarineSamrajReading) {
        if (reading.session != session || !allowed(reading.url)) return
        visited += reading.url
        reading.links.filter(::allowed).filterNot(visited::contains).filterNot(queued::contains).forEach(queued::addLast)
        val question = reading.question?.trim().orEmpty()
        val answer = reading.answer?.trim().orEmpty()
        val key = CourseAnswerBank.normalize(question) + "\u001f" + CourseAnswerBank.normalize(answer)
        if (question.length >= 12 && answer.length >= 12 && captured.add(key)) {
            pendingCapture = MarineSamrajCapture(reading.module.ifBlank { "Public questions" }, question, answer)
        }
        val count = captured.size
        if (visited.size >= maxPages) {
            progress = MarineSamrajProgress("Reached the ${maxPages}-page limit. Saved public Q&A stays in the local database.", visited.size, count, true)
            return
        }
        val next = queued.firstOrNull { it !in visited }
        if (next == null) {
            progress = MarineSamrajProgress("No more permitted public links were found on the pages visited.", visited.size, count, true)
            return
        }
        progress = MarineSamrajProgress("Opening the next permitted public page", visited.size, count)
        if (pending?.url != next || pending?.document != reading.document) pending = MarineSamrajAction(++request, reading.document, next)
    }

    private fun allowed(url: String): Boolean = runCatching {
        val value = url.lowercase()
        (value.startsWith("https://marinesamraj.com/") || value.startsWith("https://www.marinesamraj.com/")) &&
            ("/exam-prep/" in value || "/ep_home" in value) &&
            listOf("/user/", "/admin/", "/notification/", "/review-pipeline/", "/support-the-creators/").none { it in value }
    }.getOrDefault(false)
}
