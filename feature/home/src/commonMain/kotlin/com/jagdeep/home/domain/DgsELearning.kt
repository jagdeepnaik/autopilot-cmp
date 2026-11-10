package com.jagdeep.home.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DGS_ANSWER_BANK = "dgs-elearning.json"

@Serializable
@SerialName("dgsELearning")
data class DgsELearningRoutine(
    override val id: String,
    override val name: String,
    val requiredHours: Double = 18.0,
    /** User-entered credited time for the selected account/course; never inferred from the session timeout. */
    val creditedHours: Double = 0.0,
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val playbackSpeed: Double = 1.0,
    val waitAfterClickMillis: Long = 5_000,
    val answerBankFile: String = DGS_ANSWER_BANK,
) : Routine { override val kind: RoutineKind get() = RoutineKind.DgsELearning }

@Serializable
data class DgsCourseChoice(val id: String, val label: String, val code: String = "", val available: Boolean = false)

@Serializable
internal data class DgsReading(
    val type: String = "dgsReading",
    val session: String,
    val page: String = "",
    val title: String = "",
    val course: String = "",
    val courses: List<DgsCourseChoice> = emptyList(),
    val ready: Boolean = false,
    val focused: Boolean = false,
    val atBottom: Boolean = false,
    val next: Boolean? = null,
    val previous: Boolean? = null,
    val first: Boolean = false,
    val media: String = "none",
    val speed: Double? = null,
    val problem: String? = null,
)

@Serializable
internal data class DgsCommand(val session: String, val action: String, val page: String?, val pixelsPerSecond: Int, val playbackSpeed: Double, val courseId: String? = null, val courseLabel: String? = null, val selectionRequest: Long = 0, val nativeClicks: Boolean = false, val request: String? = null, val browserClicks: Boolean = true)

@Serializable
internal data class DgsBrowserAction(val type: String, val session: String, val request: String, val success: Boolean, val problem: String? = null)

@Serializable
internal data class DgsClickEvent(val type: String, val session: String, val request: String, val page: String, val action: String, val x: Int, val y: Int)

data class DgsProgress(
    val detail: String = "Sign in to DGS in one Chrome tab. Open your course and its E-BOOK manually. Assessments stay manual.",
    val page: String = "Waiting for course selection",
    val activeMillis: Long = 0,
    val pagesRead: Int = 0,
    val clicks: Long = 0,
    val reviewing: Boolean = false,
    val actualSpeed: Double? = null,
    val needsAttention: Boolean = true,
    val courses: List<DgsCourseChoice> = emptyList(),
    val courseName: String? = null,
)

/** Real foreground time only. Missing snapshots, verification, page loads and blocked videos earn no local time. */
internal class DgsSession(val session: String, private val routine: DgsELearningRoutine, private val nativeClicks: Boolean = false) {
    var progress = DgsProgress(); private set
    var completed = false; private set
    var navigations = 0L; private set
    private var course: String? = null
    private var selectionPage: String? = null
    private var selectedCourse: DgsCourseChoice? = null
    private var selectionAt: Long? = null
    private var selectionRequest=0L
    private var clickRequest=0L
    private val handledClicks=mutableSetOf<String>()
    private val issuedRequests=mutableSetOf<String>()
    private var nativeFailure: String? = null
    private var pendingAction: String? = null
    private var page: String? = null
    private var lastAt: Long? = null
    private var previousActive = false
    private var pageActive = 0L
    private var bottomAt: Long? = null
    private var pendingPage: String? = null
    private var pendingAt = 0L
    private var pendingWaitMillis=0L
    private var lastUsable=false
    private val readPages = mutableSetOf<String>()
    private var action = "pause"
    val command get() = DgsCommand(session, action, selectionPage ?: page, routine.pixelsPerSecond, routine.playbackSpeed, selectedCourse?.id, selectedCourse?.label, selectionRequest, nativeClicks=nativeClicks, request=if(action in setOf("selectCourse","next","previous","first")) "$session:$clickRequest:$action" else null)

    fun selectCourse(id: String, now: Long) {
        val selected=progress.courses.singleOrNull { it.id==id && it.available } ?: return
        if(course!=null) return
        selectedCourse=selected; selectionAt=now; selectionRequest++; clickRequest++; issuedRequests.add("$session:$clickRequest:selectCourse"); nativeFailure=null; action="selectCourse"
        progress=progress.copy(courses=emptyList(),courseName=selected.label,detail="Opening ${selected.label} in Chrome.",needsAttention=false)
    }

    fun acknowledgeBrowserAction(event: DgsBrowserAction) {
        if(event.session!=session || event.request !in issuedRequests || !handledClicks.add("browser:${event.request}")) return
        if(event.success) {progress=progress.copy(clicks=progress.clicks+1);return}
        if(command.request!=event.request) return
        val message=when(event.problem) {
            "permission" -> "Chrome click permission is missing. Reload the approved extension update before restarting."
            "busy" -> "Another browser controller or Developer Tools is using this tab. Disconnect it before restarting."
            "changed" -> "The page or focus changed before the click. Check the DGS tab and restart the routine."
            else -> "Chrome couldn't click the page button. Check the DGS tab and restart the routine."
        }
        nativeClickFailed(message)
    }

    fun claimNativeClick(event: DgsClickEvent): Boolean {
        val current=command
        return current.nativeClicks && event.session==session && event.request==current.request && event.page==current.page && event.action==current.action && handledClicks.add(event.request)
    }
    fun nativeClickSucceeded() { progress=progress.copy(clicks=progress.clicks+1) }
    fun nativeClickFailed(message: String) {
        nativeFailure=message; action="pause"; pendingAction=null
        if(selectionPage!=null) {selectedCourse=null;selectionAt=null}
        progress=progress.copy(detail=message,needsAttention=true)
    }

    fun stale() { action = "pause"; lastAt = null; previousActive = false; lastUsable=false; progress = progress.copy(detail = "Waiting for DGS page updates. Keep its tab active and Chrome in front.", needsAttention = true) }

    fun read(value: DgsReading, now: Long) {
        if (value.session != session || completed) return
        if(value.courses.isNotEmpty()) {
            lastAt=null; previousActive=false; action="pause"
            if(course!=null) { progress=progress.copy(detail="Stop this run before choosing another course and checking its credited hours.",needsAttention=true); return }
            selectionPage=value.page
            val choices=value.courses.take(100).filter { it.available && it.id.isNotBlank() && it.id.length<=128 && it.label.isNotBlank() && it.label.length<=240 }.distinctBy { it.id }
            val selected=selectedCourse
            if(selected!=null && selectionAt?.let { now-it<15_000 }==true && choices.any { it==selected }) { action="selectCourse"; return }
            selectedCourse=null; selectionAt=null
            progress=progress.copy(courses=choices,detail=nativeFailure ?: if(choices.isEmpty()) "No available courses on DGS. Check your enrolment in Chrome." else if(selected!=null) "DGS did not open the course. Select its number again, or open it manually in Chrome." else "Choose an available course in the app to open its e-book.",needsAttention=true)
            return
        }
        if(value.ready) { selectionPage=null; progress=progress.copy(courses=emptyList()) }

        if(value.ready && value.course.isNotBlank()) {
            if(course!=null && course!=value.course) { stale(); progress=progress.copy(detail="The DGS account or course changed. Stop and check credited hours before starting another run."); return }
            course=value.course
        }
        val usable = value.ready && value.focused && value.problem == null && value.page.isNotBlank()
        val active = usable && value.media in setOf("none", "playing", "ended") && pendingPage == null
        val delta = lastAt?.let { (now-it).takeIf { d -> d in 0..2_000 } } ?: 0L
        if (active && previousActive && page == value.page) {
            progress = progress.copy(activeMillis = progress.activeMillis + delta)
            pageActive += delta
        }
        if(pendingPage!=null && usable && lastUsable && page==value.page) pendingWaitMillis+=delta
        lastUsable=usable
        lastAt = now; previousActive = active
        action = "pause"
        if (!usable) {
            bottomAt = null
            progress = progress.copy(detail = value.problem ?: if (!value.focused) "Bring the DGS course tab to the front." else "Sign in, select your course and open E-BOOK. Assessments are not automated.", needsAttention = true)
            return
        }
        if (page != value.page) {
            if(pendingPage!=null) navigations++
            page = value.page; pageActive = 0; bottomAt = null; pendingPage = null; pendingAction=null; nativeFailure=null
        }
        progress = progress.copy(page = value.title.ifBlank { "E-book page" }, actualSpeed = value.speed, needsAttention = false)
        nativeFailure?.let { progress=progress.copy(detail=it,needsAttention=true); return }
        if (pendingPage != null) {
            if (pendingWaitMillis > 15_000) progress = progress.copy(detail = "The page did not change. Check Next/Previous in Chrome; navigation is paused.", needsAttention = true)
            else {action=pendingAction ?: "pause"; progress = progress.copy(detail = "Waiting for the next e-book page.") }
            return
        }
        if (value.media !in setOf("none", "ended")) {
            bottomAt = null; action = "read"
            progress = progress.copy(detail = if (value.media == "playing") "Watching video to the end. Actual speed: ${value.speed ?: "checking"}×." else "Waiting for the video. Press Play in Chrome if needed; scrolling stays paused.", needsAttention = value.media != "playing")
            return
        }
        action = "read"
        if (!value.atBottom) { bottomAt = null; progress = progress.copy(detail = "Slowly reading and scrolling this page."); return }
        if (bottomAt == null) bottomAt = now
        // Even a short page gets real reading time; no rapid Next loop to manufacture study hours.
        if (pageActive < 30_000 || now-bottomAt!! < routine.waitAfterClickMillis) {
            progress = progress.copy(detail = "At the bottom. Allowing reading time before continuing."); return
        }
        readPages += value.page
        progress = progress.copy(pagesRead = readPages.size)
        val target = ((routine.requiredHours-routine.creditedHours).coerceAtLeast(0.0)*3_600_000).toLong()
        if (value.next == false) {
            if (progress.activeMillis >= target) {
                completed = true; action = "pause"
                progress = progress.copy(detail = "Last page finished and local active-time target reached. Check DGS credited hours. Assessments remain manual.")
                return
            }
            progress = progress.copy(reviewing = true)
            if(!value.first) {
                action="pause"
                progress=progress.copy(detail="More study time remains. Open the first page manually; the routine will continue.",needsAttention=true)
                return
            }
            action="first"
        } else if(value.next==true) action="next"
        else {
            action="pause"
            progress=progress.copy(detail="Cannot confirm Next. Check the course navigation in Chrome.",needsAttention=true)
            return
        }
        pendingPage = value.page; pendingAt = now; pendingAction=action; pendingWaitMillis=0; clickRequest++
        issuedRequests.add("$session:$clickRequest:$action")
        progress = progress.copy(detail = if (progress.reviewing) "Reviewing pages while actual study time remains." else "Opening the next reading page.")
    }
}
