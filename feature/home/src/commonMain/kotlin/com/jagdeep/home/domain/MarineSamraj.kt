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
    /** Wait after AutoPilot authorizes a public-page action and before the helper clicks it. */
    val clickDelayMillis: Long = 2_000,
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
    val ready: Boolean = true,
    val error: String? = null,
    val controls: List<MarineSamrajControl> = emptyList(),
)

@Serializable
internal data class MarineSamrajControl(val id: String, val key: String)

@Serializable
internal data class MarineSamrajAction(val request: Long, val document: String, val url: String, val control: String? = null)

@Serializable
internal data class MarineSamrajActionAck(val type: String, val session: String, val request: Long, val success: Boolean)

@Serializable
internal data class MarineSamrajCommand(
    val session: String,
    val action: MarineSamrajAction? = null,
    val clickDelayMillis: Long = 2_000,
)

internal data class MarineSamrajCapture(val module: String, val question: String, val answer: String)

@Serializable
internal data class MarineSamrajCheckpoint(val visited: List<String> = emptyList(), val queued: List<String> = emptyList(), val resumeUrl: String? = null)

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
    private val clicked = mutableSetOf<String>()
    private var pendingCapture: MarineSamrajCapture? = null
    private var captureKey: String? = null
    private var lastReading: MarineSamrajReading? = null
    private var waitingForDocument: String? = null
    private var requestedUrl: String? = null
    private var failed = false
    private var baselinePages = 0
    private var resumeUrl: String? = null
    private var restoring = false
    var progress = MarineSamrajProgress(); private set
    val action: MarineSamrajAction? get() = pending
    fun waitForRestore() { restoring = true }

    fun restore(checkpoint: MarineSamrajCheckpoint) {
        restoring = false
        visited += checkpoint.visited.mapNotNull(::marineSamrajCanonicalUrl)
        queued.addAll(checkpoint.queued.filter(::allowed).distinct())
        baselinePages = visited.size
        resumeUrl = checkpoint.resumeUrl?.takeIf(::allowed)
    }

    fun checkpoint(): MarineSamrajCheckpoint? = if (failed || captureKey != null) null else MarineSamrajCheckpoint(
        visited.toList(), queued.filter { marineSamrajCanonicalUrl(it) !in visited }, resumeUrl ?: lastReading?.url)

    fun pauseForStorageFailure() {
        failed = true; pending = null
        progress = progress.copy(detail = "Collection paused: the crawl checkpoint could not be saved. Your existing answers are preserved.")
    }

    fun acknowledge(request: Long, success: Boolean) {
        if (pending?.request != request) return
        if (success) { waitingForDocument = pending?.document; requestedUrl = pending?.url }
        else { failed = true; progress = progress.copy(detail = "Chrome could not open the next public page. Stop and restart from the last saved page.") }
        pending = null
    }

    fun takeCapture(): MarineSamrajCapture? = pendingCapture.also { pendingCapture = null }

    fun captureSaved(success: Boolean) {
        val key = captureKey ?: return
        captureKey = null
        if (!success) {
            failed = true; pending = null
            progress = progress.copy(detail = "Collection paused: the answer could not be saved. No further page will be opened.")
            return
        }
        captured += key
        lastReading?.let(::read)
    }

    fun read(reading: MarineSamrajReading) {
        if (reading.session != session || !allowed(reading.url) || failed || progress.completed || restoring) return
        if (captureKey != null) return
        if (waitingForDocument == reading.document) return
        if (pending?.document == reading.document) return
        waitingForDocument = null
        lastReading = reading
        if (reading.error != null) { pending = null; progress = progress.copy(detail = reading.error); return }
        if (!reading.ready) { pending = null; progress = progress.copy(detail = "Waiting for the public page and its answer to finish loading."); return }
        val page = marineSamrajCanonicalUrl(reading.url) ?: return
        // A public alias may redirect to an already known canonical page. Remember the alias
        // too, otherwise the same navigation link is queued forever on every destination.
        requestedUrl?.let { marineSamrajCanonicalUrl(it) }?.let(visited::add)
        requestedUrl = null
        resumeUrl?.let { resume ->
            if (marineSamrajCanonicalUrl(resume) != page) {
                pending = MarineSamrajAction(++request, reading.document, resume)
                progress = progress.copy(detail = "Resuming the last saved public page.")
                return
            }
            resumeUrl = null
        }
        visited += page
        reading.links.filter(::allowed).distinctBy(::marineSamrajCanonicalUrl)
            .filterNot { marineSamrajCanonicalUrl(it) in visited }
            .filterNot { candidate -> queued.any { marineSamrajCanonicalUrl(it) == marineSamrajCanonicalUrl(candidate) } }
            .forEach(queued::addLast)
        val question = reading.question?.trim().orEmpty()
        val answer = reading.answer?.trim().orEmpty()
        val key = CourseAnswerBank.normalize(question) + "\u001f" + CourseAnswerBank.normalize(answer)
        if (question.isNotBlank() && answer.isNotBlank() && key !in captured) {
            captureKey = key
            pendingCapture = MarineSamrajCapture(reading.module.ifBlank { "Public questions" }, question, answer)
            progress = progress.copy(detail = "Saving the public answer to the routine database…", pages = visited.size)
            return
        }
        val count = captured.size
        if (visited.size - baselinePages >= maxPages) {
            pending = null
            progress = MarineSamrajProgress("Reached the ${maxPages}-page limit. Saved public Q&A stays in the local database.", visited.size, count, true)
            return
        }
        // Full-question links and Next controls take precedence over unrelated subject tabs.
        val fullQuestion = reading.links.firstOrNull {
            "/exam-prep/question/" in it && marineSamrajCanonicalUrl(it) !in visited && allowed(it)
        }
        val control = reading.controls.firstOrNull { it.key !in clicked }
        if (fullQuestion == null && control != null) {
            clicked += control.key
            pending = MarineSamrajAction(++request, reading.document, reading.url, control.id)
            progress = MarineSamrajProgress("Opening the next public question", visited.size, count)
            return
        }
        val next = fullQuestion ?: queued.firstOrNull { marineSamrajCanonicalUrl(it) !in visited }
        if (next == null) {
            pending = null
            progress = MarineSamrajProgress("No more permitted public links were found on the pages visited.", visited.size, count, true)
            return
        }
        progress = MarineSamrajProgress("Opening the next permitted public page", visited.size, count)
        if (pending?.url != next || pending?.document != reading.document) pending = MarineSamrajAction(++request, reading.document, next)
    }

    private fun allowed(url: String): Boolean = marineSamrajCanonicalUrl(url) != null
}

/** Canonical identity ignores navigation history and tracking, but keeps content-changing tabs. */
internal fun marineSamrajCanonicalUrl(value: String): String? {
    val match = Regex("^https://(?:www\\.)?marinesamraj\\.com(/[^?#]*)(?:\\?([^#]*))?(?:#.*)?$", RegexOption.IGNORE_CASE)
        .matchEntire(value) ?: return null
    val path = match.groupValues[1]
    val lower = path.lowercase()
    if (lower != "/ep_home" && lower != "/sitemap.xml" && !lower.startsWith("/exam-prep/")) return null
    if ('%' in path || '\\' in path || path.split('/').any { it == "." || it == ".." }) return null
    if (listOf("/user", "/admin", "/notification", "/review-pipeline", "/support-the-creators").any { lower.contains(it) }) return null
    val params = match.groupValues[2].split('&').mapNotNull {
        val pair = it.split('=', limit = 2)
        pair.takeIf { pair.size == 2 && pair[0] in setOf("examLevelID", "tabID", "questionID") }?.let { pair[0] to pair[1] }
    }.toMap().toMutableMap()
    if (params["tabID"]?.uppercase() == "BOOKMARKS") return null
    if (lower.startsWith("/exam-prep/subject/") && "tabID" !in params) params["tabID"] = "PAPERS"
    if (lower.startsWith("/exam-prep/question/")) params["tabID"] = "ANSWER"
    return "https://marinesamraj.com" + (if (lower == "/ep_home") "/EP_Home" else path) +
        params.entries.sortedBy { it.key }.joinToString("&", prefix = if (params.isEmpty()) "" else "?") { "${it.key}=${it.value}" }
}
