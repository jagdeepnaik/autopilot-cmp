package com.jagdeep.home.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@kotlinx.serialization.SerialName("courseAutomation")
data class CourseAutomationRoutine(
    override val id: String,
    override val name: String,
    val waitAfterClickMillis: Long = 5_000,
    val playbackSpeed: Double = 1.0,
    val answerAssessments: Boolean = false,
    val assessmentMode: AssessmentMode = AssessmentMode.Review,
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.CourseAutomation
}

@Serializable
enum class AssessmentMode { Manual, Review, Automatic }

/** Only compact semantic descriptions cross the bridge, never form values or page HTML. */
@Serializable
internal data class CourseElement(
    val id: String,
    val tag: String = "button",
    val label: String = "",
    val context: String = "",
    val href: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val expanded: Boolean? = null,
    val svg: Boolean = false,
    val paused: Boolean = true,
    val ended: Boolean = false,
    val decorative: Boolean = false,
    val courseTitle: String? = null,
)

@Serializable
internal data class CourseSnapshot(
    val session: String,
    val document: String,
    val path: String,
    val top: Boolean = true,
    val ready: Boolean = true,
    val truncated: Boolean = false,
    val elements: List<CourseElement> = emptyList(),
    val assessment: CourseAssessment? = null,
    val profileName: String? = null,
    val progress: ProgressTable? = null,
)

@Serializable
internal data class CourseAction(val request: Long, val document: String, val element: String, val kind: String = "click", val expectedLabel: String? = null)

@Serializable
internal data class CourseCommand(val session: String, val action: CourseAction? = null, val playbackSpeed: Double = 1.0)

/** App-owned decisions; the extension only describes elements and executes a specific command. */
internal class CourseAutomationSession(val session: String, private val waitAfterClickMillis: Long = 0, playbackSpeed: Double = 1.0, private val answerAssessments: Boolean = false,
    private val assessmentMode: AssessmentMode = AssessmentMode.Review) {
    var progressCheckpoint = 0; private set
    private var savedPassedTitles = emptySet<String>()
    private var savedUnsubmittedTitles = emptySet<String>()
    fun configureProgress(summary: CourseProgressSummary?) {
        savedPassedTitles = summary?.records.orEmpty().filter { it.kind == "assessment" && it.status == "passed" }
            .map { CourseAnswerBank.normalize(it.course) }.toSet()
        savedUnsubmittedTitles = summary?.records.orEmpty().filter { it.kind == "assessment" && it.status in setOf("unsubmitted", "failed") }
            .map { CourseAnswerBank.normalize(it.course) }.toSet() - savedPassedTitles
    }
    private var answerBank: CourseAnswerBank? = null
    private var persistedAnswerBank: CourseAnswerBank? = null
    private val manualAnswers = mutableMapOf<String, Pair<String, CourseAnswer>>()
    fun configureAnswers(bank: CourseAnswerBank?) { persistedAnswerBank = bank; answerBank = bank }
    var approval: CourseApprovalRequest? = null
        private set
    var attention: String? = null
        private set
    var manualAnswer: ManualAnswerRequest? = null
        private set
    var failure: AssessmentFailureRequest? = null
        private set
    private val assessmentEpochs = mutableMapOf<String, Int>()
    private val approvedRetryCourses = mutableSetOf<String>()
    private var authorizedRetryToken: String? = null
    private var retryStartedAt: Long? = null
    private val observedQuestions = mutableMapOf<String, MutableMap<String, CourseQuestion>>()

    fun acceptManualAnswer(token: String, savedBank: CourseAnswerBank? = null): Boolean {
        val request = manualAnswer?.takeIf { it.token == token } ?: return false
        val row = CourseAnswer(request.question, request.options, request.answer)
        if (savedBank != null) persistedAnswerBank = savedBank
        manualAnswers[token] = request.module to row
        var combined = persistedAnswerBank
        for ((module, value) in manualAnswers.values) {
            combined = combined?.withAnswer(module, value)
                ?: CourseAnswerBank.decode(Json.encodeToString(mapOf(module to listOf(value)))) ?: return false
        }
        answerBank = combined
        manualAnswer = null
        attention = null
        return true
    }

    fun retryAssessment(token: String): Boolean {
        if (failure?.token != token || failure?.canRetry != true) return false
        authorizedRetryToken = token
        failure = null
        return true
    }
    private val approvedStarts = mutableSetOf<String>()
    private val explicitlyStartedAssessments = mutableSetOf<String>()
    private var approvedSubmission: String? = null
    fun approveAssessment(token: String) {
        val request = approval?.takeIf { it.token == token } ?: return
        if (request.kind == CourseApprovalKind.StartAssessment) approvedStarts.add(coursePath ?: return)
        else approvedSubmission = token
        approval = null
    }
    val playbackSpeed = playbackSpeed.takeIf { it in setOf(1.0, 1.5, 2.0) } ?: 1.0
    var action: CourseAction? = null
        private set
    var detail: String = "Open the course dashboard in Chrome."
        private set
    var clicks: Long = 0
        private set
    var completed = false
        private set
    private var sequence = 0L
    private var pendingAt = 0L
    private var nextActionAt = 0L
    private var blockedDocument: String? = null
    private var blockedPath: String? = null
    private var lastPath: String? = null
    private var lastLesson: String? = null
    private val completedLessons = mutableSetOf<String>()
    private val completedLessonPages = mutableSetOf<String>()
    private val chapterLessons = mutableMapOf<String, MutableSet<String>>()
    private val completedChapters = mutableSetOf<String>()
    private val completedCourses = mutableSetOf<String>()
    private val videoFinishedCourses = mutableSetOf<String>()
    private val passedAssessments = mutableSetOf<String>()
    private val submittedAssessments = mutableSetOf<String>()
    private val confirmedAnswers = mutableMapOf<String, MutableMap<String, String>>()
    private var assessmentCourse: String? = null
    private var returnToCourseAfterLesson = false
    private val confirmedSubmissions = mutableSetOf<String>()
    private val finishedDashboardPages = mutableSetOf<String>()
    private val completedCards = mutableSetOf<String>()
    private var openedCard: String? = null
    private val attempted = mutableSetOf<String>()
    private val videos = mutableMapOf<String, CourseElement>()
    private var completionCandidate: String? = null
    private var completionSince = 0L
    private var coursePath: String? = null
    private var currentView: CourseView? = null

    private enum class CourseView { Dashboard, Course, Lesson, Assessment }

    /** A SPA can keep Chrome's original sender URL. The visible controls identify the current
     * view independently, while course identity still comes from a real same-site breadcrumb. */
    private fun viewOf(snapshot: CourseSnapshot): CourseView {
        val segments = snapshot.path.split('/').filter(String::isNotEmpty)
        val controls = snapshot.elements
        if (snapshot.top && snapshot.assessment != null) return CourseView.Assessment
        if (snapshot.top && controls.any { it.label.equals("Watch video", true) || it.label.equals("Rewatch video", true) || it.label.equals("Resume video", true) }) return CourseView.Course
        if (snapshot.top && controls.any { it.visible && it.tag in setOf("h1", "h2", "h3", "h4") && it.label.trim().equals("All Courses", true) }) return CourseView.Dashboard
        if (controls.any { it.label.equals("Back to Course", true) } ||
            snapshot.top && controls.any { it.visible && it.tag in setOf("h1", "h2", "h3", "h4") && it.label.trim().equals("Video Completed!", true) } ||
            controls.any { it.tag == "video" && !it.decorative } && (snapshot.top || lastLesson != null) ||
            snapshot.top && controls.any { it.tag in setOf("h1", "h2", "h3", "h4") && Regex("^Part\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }) return CourseView.Lesson
        if (snapshot.top && controls.any { it.expanded != null && isChapter(it) }) return CourseView.Course
        if (segments.size >= 4) return CourseView.Lesson
        if (segments.size == 3) return CourseView.Course
        if (snapshot.top && controls.any { isSingleCourse(it.context) }) return CourseView.Dashboard
        return currentView ?: CourseView.Dashboard
    }

    fun acknowledge(request: Long, success: Boolean, now: Long = pendingAt) {
        val pending = action?.takeIf { it.request == request } ?: return
        if (success && pending.kind != "play") clicks++
        if (success) nextActionAt = maxOf(nextActionAt, now + waitAfterClickMillis.coerceIn(0, 86_400_000))
        if (!success && pending.kind != "play") {
            detail = "The page changed before the action. Stop and start again to retry."
            blockedDocument = pending.document
        }
        action = null
    }

    fun read(snapshot: CourseSnapshot, now: Long) {
        if (snapshot.session != session || !snapshot.ready) return
        if (snapshot.truncated) { detail = "This page has too many controls to read safely. Stop and continue in Chrome."; return }
        val reportedPath = snapshot.path.trimEnd('/')
        if (!Regex("/dashboard(?:/courses(?:/[A-Za-z0-9_-]+(?:/(?:test/[A-Za-z0-9_-]+|assessment/result/[A-Za-z0-9_-]+|[A-Za-z0-9_-]+))?)?)?").matches(reportedPath)) return
        val view = viewOf(snapshot)
        if (snapshot.assessment?.result != "failed") authorizedRetryToken = null
        attention = null
        manualAnswer = null
        failure = null
        if (view != CourseView.Dashboard) {
            val breadcrumb = snapshot.elements.mapNotNull { it.href }.firstOrNull { Regex("/dashboard/courses/[A-Za-z0-9_-]+").matches(it) }
            coursePath = breadcrumb ?: reportedPath.split('/').takeIf { it.size >= 4 }?.take(4)?.joinToString("/") ?: coursePath
        }
        val path = coursePath ?: reportedPath
        // Part numbers repeat between chapters. A real lesson breadcrumb is stronger identity
        // than Part:1 or Chrome's potentially stale sender URL.
        val lessonPage = snapshot.elements.firstOrNull {
            it.visible && it.tag == "a" && it.href?.let { href -> Regex("/dashboard/courses/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+").matches(href) } == true
        }?.href
        val pageId = when (view) {
            CourseView.Dashboard -> "dashboard:" + snapshot.elements.filter { isSingleCourse(it.context) }
                .map { it.context.replace(Regex("not started|in progress|completed", RegexOption.IGNORE_CASE), "").trim() }.distinct().sorted().joinToString("|").hashCode()
            CourseView.Course -> "course:$path"
            CourseView.Assessment -> "assessment:$path:${assessmentEpochs[path] ?: 0}:" + snapshot.assessment?.questions?.joinToString { it.id + it.text }
            CourseView.Lesson -> "lesson:$path:" + (lessonPage ?: snapshot.elements.firstOrNull {
                it.visible && it.tag in setOf("h1", "h2", "h3", "h4") && Regex("^Part\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it.label)
            }?.label ?: reportedPath.takeIf { it.split('/').filter(String::isNotEmpty).size >= 4 } ?: lastLesson ?: reportedPath)
        }
        if (lastPath != pageId) {
            approval = null
            approvedSubmission = null
            lastPath = pageId
            videos.clear()
            blockedDocument = null
            blockedPath = null
            action = null
            completionCandidate = null
        }
        currentView = view
        if (action != null) {
            if (now - pendingAt > 15_000) {
                detail = "Chrome did not confirm the action. Stop and start again to retry."
                blockedDocument = action?.document
                action = null
            }
            return
        }
        if (now < nextActionAt) {
            detail = "Waiting ${((nextActionAt - now + 999) / 1000)} seconds before the next action."
            return
        }
        if (blockedDocument == snapshot.document) return
        if (blockedPath == pageId) {
            detail = "Waiting for the page to open. Stop and start again if Chrome stays here."
            return
        }
        val elements = snapshot.elements.filter { it.visible && it.enabled }
        fun issue(element: CourseElement, kind: String = "click", message: String, repeat: Boolean = false) {
            val key = "$pageId:${snapshot.document}:${element.id}:$kind"
            if (!repeat && !attempted.add(key)) { detail = if (kind == "play") "Press play in Chrome to continue. Paused videos are left paused." else "Waiting for the page to change. Stop and start again if it stays here."; return }
            action = CourseAction(++sequence, snapshot.document, element.id, kind, element.label.takeIf { kind.startsWith("assessment") })
            pendingAt = now
            detail = message
        }
        fun requireApproval(kind: CourseApprovalKind, module: String, signature: String): Boolean {
            if (assessmentMode == AssessmentMode.Automatic && !(kind == CourseApprovalKind.StartAssessment && CourseAnswerBank.normalize(module) in savedUnsubmittedTitles)) return true
            val token = "$pageId:$kind:$signature"
            if (kind == CourseApprovalKind.StartAssessment && path in approvedStarts) return true
            if (kind == CourseApprovalKind.SubmitAssessment && approvedSubmission == token) return true
            approval = CourseApprovalRequest(token, kind, module)
            detail = if (kind == CourseApprovalKind.StartAssessment) {
                if (path in videoFinishedCourses) "All videos finished. Approve starting $module assessment; it may be timed or consume an attempt."
                else "Approve starting $module assessment now. Course videos may be incomplete; this may be timed or consume an attempt."
            }
                else "All displayed answers matched. Review $module and approve submission in the app."
            return false
        }
        if (view == CourseView.Assessment) {
            val assessment = snapshot.assessment ?: return
            assessment.questions.forEach { observedQuestions.getOrPut(path) { mutableMapOf() }[CourseAnswerBank.normalize(it.text) + it.options.map { option -> CourseAnswerBank.optionText(option.text) }.sorted().joinToString("|")] = it }
            if (assessment.result == "failed") {
                val failureToken = "$reportedPath:${snapshot.document}:${assessmentEpochs[path] ?: 0}:failed"
                if (authorizedRetryToken == failureToken) {
                    authorizedRetryToken = null
                    val retry = elements.firstOrNull { it.id == assessment.retry && it.label.trim().equals("Re-attempt", true) }
                    if (retry == null) { detail = "Retry unavailable. Use the website's retry control manually."; return }
                    submittedAssessments.remove(path)
                    confirmedSubmissions.remove(path)
                    explicitlyStartedAssessments.remove(path)
                    confirmedAnswers.remove(path)
                    observedQuestions.remove(path)
                    manualAnswers.clear()
                    answerBank = persistedAnswerBank
                    approvedSubmission = null
                    approvedStarts.add(path)
                    approvedRetryCourses.add(path)
                    assessmentCourse = path
                    assessmentEpochs[path] = (assessmentEpochs[path] ?: 0) + 1
                    retryStartedAt = now
                    issue(retry, "assessmentRetry", "Starting one explicitly approved retry using this routine's assessment mode.")
                    return
                }
                if (retryStartedAt?.let { now - it < 15_000 } == true) {
                    detail = "Waiting for the approved retry to open."
                    return
                }
                retryStartedAt = null
                authorizedRetryToken = null
                val review = observedQuestions[path]?.values.orEmpty().distinctBy { CourseAnswerBank.normalize(it.text) to it.options.map { option -> CourseAnswerBank.optionText(option.text) }.sorted() }
                    .mapIndexed { index, question -> AssessmentReviewQuestion(index.toString(), question.text, question.options.map { it.text },
                        question.options.singleOrNull { it.selected }?.text, persistedAnswerBank?.entry(assessment.module, question)?.answer) }
                failure = AssessmentFailureRequest(failureToken, assessment.module,
                    elements.any { it.id == assessment.retry && it.label.trim().equals("Re-attempt", true) }, review)
                detail = "The assessment did not pass. Review it in Chrome; it will not be retried automatically."
                return
            }
            retryStartedAt = null
            if (answerAssessments && assessment.start != null) {
                if (path in explicitlyStartedAssessments || path in submittedAssessments) {
                    detail = "This assessment was already started in this run. No additional attempt will be started."
                    return
                }
                if (answerBank?.module(assessment.module) == null) {
                    attention = "$path:module:${assessment.module}"
                    detail = "No local answer bank matches this module. Stop and check sagarmeinyog.json."
                    return
                }
                if (!requireApproval(CourseApprovalKind.StartAssessment, assessment.module, path)) return
                val start = elements.firstOrNull { it.id == assessment.start && it.label.trim().equals("Start Assessment", true) }
                if (start != null) {
                    assessmentCourse = path
                    explicitlyStartedAssessments.add(path)
                    issue(start, "assessmentStart", "Starting the explicitly approved module assessment.")
                } else detail = "Waiting for the recognized Start Assessment control."
                return
            }
            if (!answerAssessments || (path !in videoFinishedCourses && path !in explicitlyStartedAssessments && path !in approvedRetryCourses) || assessmentCourse != path) {
                detail = "Assessment paused. Start from the module and finish all its videos first."
                return
            }
            if (assessment.result == "passed") {
                passedAssessments.add(path)
                if (completedCourses.add(path)) progressCheckpoint++
                openedCard?.let(completedCards::add)
                val back = elements.firstOrNull { it.href == "/dashboard" }
                    ?: elements.firstOrNull { it.href == path }
                    ?: elements.firstOrNull { it.label.trim().lowercase() in setOf("back to dashboard", "back to course", "back home") }
                if (back != null) { issue(back, "assessmentReturn", "Assessment passed. Returning to the dashboard."); blockedPath = pageId }
                else detail = "Assessment passed. Waiting for the dashboard or course link."
                return
            }
            if (path in submittedAssessments) {
                val confirm = elements.firstOrNull { it.id == assessment.confirmSubmit && it.label.trim().equals("End Test", true) }
                if (confirm != null && confirmedSubmissions.add(path)) {
                    issue(confirm, "assessmentConfirmSubmit", "Confirming the approved assessment submission once.")
                } else detail = "Assessment submitted. Waiting for an explicit pass result; no retry will be sent."
                return
            }
            val bank = answerBank
            val saved = bank?.module(assessment.module)
            if (saved == null && assessment.questions.isEmpty()) {
                attention = "$path:module:${assessment.module}"
                detail = "No local answer bank matches this module. Stop and check sagarmeinyog.json."
                return
            }
            if (assessment.questions.isEmpty()) { detail = "Waiting for assessment questions; unknown start or confirmation buttons are left for review."; return }
            if (assessment.questions.map { it.id }.toSet().size != assessment.questions.size) {
                detail = "Assessment paused: duplicate question controls need review."; return
            }
            val answers = assessment.questions.map { q -> q to bank?.answer(assessment.module, q) }
            val unknown = answers.firstOrNull { it.second == null || (assessmentMode == AssessmentMode.Manual && it.second?.selected != true) }?.first
            if (unknown != null) {
                attention = "$path:${CourseAnswerBank.normalize(unknown.text)}:" +
                    unknown.options.map { CourseAnswerBank.normalize(it.text) }.sorted().joinToString("|")
                val selected = unknown.options.singleOrNull { it.selected && it.visible && it.enabled }
                if (selected != null && unknown.options.map { CourseAnswerBank.optionText(it.text) }.distinct().size == unknown.options.size) {
                    manualAnswer = ManualAnswerRequest("$attention:${CourseAnswerBank.optionText(selected.text)}", assessment.module, unknown.text, unknown.options.map { it.text }, selected.text,
                        persistedAnswerBank?.entry(assessment.module, unknown)?.answer)
                }
                detail = if (assessmentMode == AssessmentMode.Manual) "Choose an answer manually in Chrome for “${unknown.text.take(180)}”."
                    else "Assessment paused: no exact saved answer for “${unknown.text.take(180)}”. Check the question and options in sagarmeinyog.json."
                return
            }
            val confirmed = confirmedAnswers.getOrPut(path) { mutableMapOf() }
            answers.forEach { (q, answer) ->
                if (answer!!.selected) confirmed[q.id] = CourseAnswerBank.normalize(q.text)
                else confirmed.remove(q.id)
            }
            val unanswered = answers.firstOrNull { !it.second!!.selected }
            if (unanswered != null) {
                val option = unanswered.second!!
                issue(CourseElement(option.id, tag = "radio", label = option.text), "assessmentAnswer", "Selecting a matching answer from the local file.")
                return
            }
            val next = elements.firstOrNull { it.id == assessment.next && it.label.trim().lowercase() in setOf("next", "next question") }
            val expectedCount = assessment.totalQuestions ?: saved?.size ?: 0
            if (next != null && confirmed.size < expectedCount) { issue(next, "assessmentNext", "Opening the next assessment question."); return }
            // A full known bank must be covered, with every displayed answer observed selected.
            // Never submit based on click acknowledgements or a guessed last-page button.
            if (confirmed.size != expectedCount || (assessment.totalQuestions == null && !saved.orEmpty().all { CourseAnswerBank.normalize(it.question) in confirmed.values })) {
                detail = "Assessment paused: not all assessment questions have been confirmed."; return
            }
            val submit = elements.firstOrNull { it.id == assessment.submit && it.label.trim().lowercase() in setOf("submit", "submit assessment", "submit answers", "end test") }
            if (submit != null) {
                if (!requireApproval(CourseApprovalKind.SubmitAssessment, assessment.module, confirmed.entries.sortedBy { it.key }.joinToString() + assessment.questions.toString())) return
                submittedAssessments.add(path)
                approvedSubmission = null
                issue(submit, "assessmentSubmit", "Submitting the fully matched assessment once.")
            } else detail = "All known answers are selected. Waiting for a recognized assessment submit control."
            return
        }
        if (view == CourseView.Lesson) {
            if (lessonPage != null && lessonPage in completedLessonPages && snapshot.top) {
                val back = elements.firstOrNull { it.href == coursePath && it.tag == "a" }
                    ?: elements.firstOrNull { it.label.equals("Back to Course", true) }
                if (back != null) {
                    issue(back, message = "This video is already finished. Checking the remaining module videos.")
                    blockedPath = pageId
                    return
                }
            }
            // The live site unmounts the media element immediately when playback ends.
            // Only the explicit completion heading is evidence; a missing player is not.
            val completionScreen = snapshot.top && elements.any {
                it.tag in setOf("h1", "h2", "h3", "h4") && it.label.trim().equals("Video Completed!", true)
            }
            videos.keys.removeAll { it.startsWith("${snapshot.document}:") }
            snapshot.elements.filter { it.tag == "video" && !it.decorative }.forEach { videos["${snapshot.document}:${it.id}"] = it }
            val video = elements.firstOrNull { it.tag == "video" && !it.decorative && !it.ended }
            if (video != null) {
                if (!video.paused) { detail = "Watching the video. It continues when the video ends."; return }
                // Use the player's own start control first: it may initialize its media source.
                // Calling HTMLVideoElement.play() alone can fail before that initialization.
                val playButton = elements.firstOrNull {
                    it.tag == "button" && it.label.trim().lowercase() in setOf("play", "play video") &&
                        "$pageId:${snapshot.document}:${it.id}:click" !in attempted
                }
                if (playButton != null) {
                    issue(playButton, message = "Starting the video with its player control.")
                    return
                }
                issue(video, "play", "Starting the video. If Chrome refuses, press play in Chrome.")
                return
            }
            if (videos.isEmpty() && !completionScreen) {
                val play = elements.firstOrNull { it.label.lowercase() in setOf("play", "play video") }
                if (play != null) issue(play, message = "Opening the video player.")
                else detail = "Waiting for a video. If the player needs a click, press play in Chrome."
                return
            }
            if (!completionScreen && videos.values.any { !it.ended }) { detail = "Waiting for the video to finish. Press play in Chrome if paused."; return }
            lastLesson?.let(completedLessons::add)
            lessonPage?.let(completedLessonPages::add)
            val courseReturn = elements.firstOrNull { it.label.equals("Back to Course", true) }
                ?: elements.firstOrNull { it.tag == "a" && it.href == coursePath }
            if (returnToCourseAfterLesson && courseReturn != null && snapshot.top) {
                returnToCourseAfterLesson = false
                issue(courseReturn, message = "Video finished. Checking module completion before replaying completed lessons.")
                blockedPath = pageId
                return
            }
            val next = elements.firstOrNull { it.tag in setOf("button", "a") && it.label.trim().equals("Next", true) }
            if (next != null && snapshot.top) {
                issue(next, message = "Video finished. Opening the next video.")
                blockedPath = pageId
                lastLesson = null
                return
            }
            val back = elements.firstOrNull { it.label.equals("Back to Course", true) }
                ?: elements.firstOrNull { it.tag == "a" && it.href == coursePath }
            if (back != null && snapshot.top) {
                issue(back, message = "Video finished. Returning to the course.")
                blockedPath = pageId
            } else detail = "Video finished. Waiting for Back to Course; quizzes remain manual."
            return
        }
        if (!snapshot.top) return
        if (view == CourseView.Course) {
            // Completed rows change their button to Rewatch video on the live site. Keep them in
            // the row order so completing Part 3 cannot make Part 4 inherit Part 3's fallback key.
            val allLessons = snapshot.elements.filter { it.label.equals("Watch video", true) || it.label.equals("Rewatch video", true) || it.label.equals("Resume video", true) }
            val lessons = elements.filter { it.label.equals("Watch video", true) || it.label.equals("Resume video", true) }
            val expandedChapter = elements.firstOrNull { it.expanded == true && isChapter(it) }
            fun chapterKey(element: CourseElement) = "$path:" + element.label.lowercase()
                .replace(Regex("not started|in progress|not completed|incomplete|completed|\\d+\\s*/\\s*\\d+|·"), "")
                .replace(Regex("\\s+"), " ").trim()
            val chapter = expandedChapter?.let(::chapterKey)
            fun key(element: CourseElement) = lessonKey(chapter ?: path, element, allLessons.indexOf(element))
            fun finished(element: CourseElement) = element.label.equals("Rewatch video", true) || done(element.context) || key(element) in completedLessons
            if (chapter != null) {
                allLessons.filter { it.visible }.forEach { element ->
                    chapterLessons.getOrPut(chapter) { mutableSetOf() }.add(key(element))
                    if (finished(element)) completedLessons.add(key(element))
                }
            }
            chapterLessons.forEach { (id, knownLessons) ->
                if (knownLessons.isNotEmpty() && completedLessons.containsAll(knownLessons)) completedChapters.add(id)
            }
            val next = lessons.firstOrNull { !finished(it) }
            if (next != null) {
                videoFinishedCourses.remove(path)
                approval = null
                returnToCourseAfterLesson = allLessons.any(::finished) && allLessons.filter { it.id != next.id }.all(::finished) &&
                    elements.filter { it.expanded != null && isChapter(it) && it.id != expandedChapter?.id }.all { done(it.label) || chapterKey(it) in completedChapters }
                lastLesson = key(next)
                issue(next, message = "Opening unfinished video lesson ${allLessons.indexOf(next) + 1}.")
                blockedPath = pageId
                return
            }
            val nextChapter = elements.firstOrNull { it.expanded == false && isChapter(it) && chapterKey(it) !in completedChapters && !done(it.label) && !forbidden(it.label) && "$pageId:${snapshot.document}:${it.id}:click" !in attempted }
            if (nextChapter != null) { issue(nextChapter, message = "Opening the next chapter."); return }
            val chapters = elements.filter { it.expanded != null && isChapter(it) }
            if (chapters.any { !done(it.label) && chapterKey(it) !in completedChapters }) {
                detail = "Waiting for the remaining module chapters to become available."
                return
            }
            if (allLessons.isEmpty() && chapters.isEmpty()) { detail = "Waiting for video lessons. Open a chapter in Chrome if needed."; return }
            if (allLessons.any { !finished(it) }) {
                detail = "Waiting for the next lesson to become available in Chrome."
                return
            }
            if (!settled(snapshot, now)) return
            videoFinishedCourses.add(path)
            if (elements.any { it.href == path && CourseAnswerBank.normalize(it.label) in savedPassedTitles }) passedAssessments.add(path)
            if (answerAssessments && path !in passedAssessments) {
                if (answerBank == null) { detail = "Videos finished. Add a valid sagarmeinyog.json answer bank before starting the assessment."; return }
                val assessment = elements.firstOrNull { it.label.trim().equals("Assessment", true) }
                if (assessment != null) {
                    val module = elements.firstOrNull { it.href == path }?.label?.takeIf { it.isNotBlank() } ?: path
                    if (!requireApproval(CourseApprovalKind.StartAssessment, module, path)) return
                    assessmentCourse = path
                    issue(assessment, "assessmentOpen", "All module videos finished. Opening its assessment.")
                } else detail = "Videos finished. Waiting for this module's Assessment button."
                return
            }
            if (completedCourses.add(path)) progressCheckpoint++
            openedCard?.let(completedCards::add)
            val back = elements.firstOrNull { it.href in setOf("/dashboard", "/dashboard/courses") && !forbidden(it.label) }
                ?: elements.firstOrNull { it.label.lowercase() in setOf("back to dashboard", "dashboard", "back to courses", "all courses") }
            if (back != null) { issue(back, message = "Videos finished. Returning to the dashboard."); blockedPath = pageId }
            else { completed = true; detail = "Available videos finished. Quizzes and assessments remain yours to complete." }
            return
        }
        // This only retries loading the dashboard, never a failed assessment attempt.
        val loadRetry = elements.firstOrNull { it.tag == "button" && it.label.trim().equals("Try again", true) }
        if (loadRetry != null) {
            issue(loadRetry, repeat = true, message = "Course list unavailable. Retrying; waiting at least 10 seconds between retries. Stop to cancel.")
            nextActionAt = now + maxOf(10_000L, waitAfterClickMillis.coerceIn(0, 86_400_000))
            return
        }
        val allCards = elements.filter { !forbidden(it.label) && isSingleCourse(it.context) }
        val cards = allCards.filter { !done(it.context) && it.context !in completedCards }
        val next = cards.groupBy { it.context }.values.firstNotNullOfOrNull { controls ->
            controls.firstOrNull { it.href?.let { href -> href !in completedCourses && Regex("/dashboard/courses/[A-Za-z0-9_-]+").matches(href) } == true }
                ?: controls.lastOrNull { it.svg && it.label.isBlank() }
        }
        if (next != null) { openedCard = next.context; issue(next, message = "Opening the next unfinished course."); blockedPath = pageId }
        else if (allCards.isNotEmpty() && cards.isEmpty() && settled(snapshot, now)) {
            val nextPage = elements.firstOrNull { it.label.trim().equals("Next", true) && it.tag in setOf("button", "a") }
            if (nextPage != null && finishedDashboardPages.add(pageId)) {
                issue(nextPage, message = "This dashboard page is finished. Opening the next page of modules.")
                blockedPath = pageId
            } else {
                completed = true
                detail = if (answerAssessments) "Available modules and assessments finished." else "Available course videos finished. Quizzes and assessments remain manual."
            }
        }
        else detail = if (elements.any { it.label.trim().equals("Try again", true) })
            "The website could not load the course list. Use Try again in Chrome when the site is available."
        else "Waiting for available courses. If the list is empty, check the website for a loading or connection error."
    }

    private fun done(text: String) = text.contains("completed", true) && !Regex("not started|in progress|not completed|incomplete", RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun isChapter(element: CourseElement) = Regex("\\bmaterial\\s*s?\\b|\\bchapter\\b", RegexOption.IGNORE_CASE).containsMatchIn(element.label)
    // The live page's header controls inherit text from the whole dashboard. Only a single card's
    // metrics count; page-wide text can otherwise make the notification bell look like a course.
    private fun isSingleCourse(text: String): Boolean = text.contains("duration", true) &&
        Regex("min\\.?\\s*pass", RegexOption.IGNORE_CASE).findAll(text).count() == 1 &&
        Regex("course\\s+id", RegexOption.IGNORE_CASE).findAll(text).count() <= 1
    private fun settled(snapshot: CourseSnapshot, now: Long): Boolean {
        val candidate = snapshot.path + snapshot.elements.joinToString { "${it.id}:${it.context}:${it.enabled}:${it.visible}" }
        if (completionCandidate != candidate) { completionCandidate = candidate; completionSince = now }
        if (now - completionSince < 2000) { detail = "Checking that all available videos have finished loading."; return false }
        return true
    }
    private fun lessonKey(path: String, element: CourseElement, index: Int) = "$path:" + (element.href ?: element.context.lowercase()
        .replace(Regex("not started|in progress|not completed|incomplete|completed|rewatch video|watch video|resume video"), "")
        .replace(Regex("\\s+"), " ").trim() + ":row:$index")
    private fun forbidden(text: String) = Regex("quiz|assessment|exam|submit|delete|purchase", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
