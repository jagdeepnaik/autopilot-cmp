package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.ForegroundApps
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.HotKeyMonitor
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

private val SPOT = ScreenPoint(640, 880)

@OptIn(ExperimentalCoroutinesApi::class)
class PageControllerTest {
    @Test fun `DGS native clicks require the exact pending command and foreground Chrome and ignore duplicates`() = runTest {
        val bridge=FakeBridge();val desktop=FakeDesktop().apply {frontApp=BROWSER_APP_NAME}
        val controller=PageController(desktop,bridge,MutableStateFlow(AppPreferences()),backgroundScope,FakeRunHistory(),{currentTime},{"dgs"})
        runCurrent();assertNull(controller.start(DgsELearningRoutine("r","DGS")));runCurrent()
        fun command()=Json.decodeFromString(HelperCommand.serializer(),bridge.published.last()).dgs!!
        val session=command().session
        val home=DgsReading(session=session,page="home",title="DGS course list",courses=listOf(DgsCourseChoice("a","Example",available=true)))
        bridge.messages.emit(Json.encodeToString(DgsReading.serializer(),home));runCurrent()
        controller.selectDgsCourse("a");runCurrent()
        val pending=command();assertTrue(pending.nativeClicks)
        val event=DgsClickEvent("dgsClick",session,pending.request!!,"home","selectCourse",200,300)
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),event.copy(session="old")));runCurrent()
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),event.copy(x=100001)));runCurrent()
        assertTrue(desktop.clicks.isEmpty())
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),event));runCurrent()
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),event));runCurrent()
        assertEquals(listOf<ScreenPoint?>(ScreenPoint(200,300)),desktop.clicks.toList())
        assertEquals(1,(controller.state.value.activity as PageActivity.Dgs).progress.clicks)
        controller.stop();runCurrent()
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),event));runCurrent()
        assertEquals(1,desktop.clicks.size)
    }
    @Test fun `DGS refuses native mouse input while another app is foreground`() = runTest {
        val bridge=FakeBridge();val desktop=FakeDesktop().apply {frontApp="Another app"}
        val controller=PageController(desktop,bridge,MutableStateFlow(AppPreferences()),backgroundScope,FakeRunHistory(),{currentTime},{"dgs"})
        runCurrent();controller.start(DgsELearningRoutine("r","DGS"));runCurrent()
        val session=Json.decodeFromString(HelperCommand.serializer(),bridge.published.last()).dgs!!.session
        bridge.messages.emit(Json.encodeToString(DgsReading.serializer(),DgsReading(session=session,page="home",courses=listOf(DgsCourseChoice("a","Example",available=true)))));runCurrent()
        controller.selectDgsCourse("a");runCurrent()
        val pending=Json.decodeFromString(HelperCommand.serializer(),bridge.published.last()).dgs!!
        bridge.messages.emit(Json.encodeToString(DgsClickEvent.serializer(),DgsClickEvent("dgsClick",session,pending.request!!,"home","selectCourse",200,300)));runCurrent()
        assertTrue(desktop.clicks.isEmpty());assertTrue((controller.state.value.activity as PageActivity.Dgs).progress.needsAttention)
        controller.stop();runCurrent()
    }

    @Test fun `DGS manual opening needs no input access and isolates stale sessions and removes its commands on stop`() = runTest {
        val bridge=FakeBridge()
        val desktop=FakeDesktop().apply {inputAccess=InputAccess.NeedsPermission}
        val controller=PageController(desktop,bridge,MutableStateFlow(AppPreferences()),backgroundScope,FakeRunHistory(),{currentTime},{"dgs"})
        runCurrent()
        assertNull(controller.start(DgsELearningRoutine("r","DGS")))
        runCurrent()
        val command=Json.parseToJsonElement(bridge.published.last()) as kotlinx.serialization.json.JsonObject
        val session=(command["dgs"] as kotlinx.serialization.json.JsonObject)["session"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        bridge.messages.emit("""{"type":"dgsReading","session":"old","page":"one","title":"Wrong","ready":true,"focused":true}""")
        runCurrent()
        assertEquals("Waiting for course selection",(controller.state.value.activity as PageActivity.Dgs).progress.page)
        bridge.messages.emit("""{"type":"dgsReading","session":"$session","page":"one","title":"Selected lesson","ready":true,"focused":true,"previous":false}""")
        runCurrent()
        assertTrue((controller.state.value.activity as PageActivity.Dgs).progress.needsAttention)
        assertEquals("pause",Json.decodeFromString(HelperCommand.serializer(),bridge.published.last()).dgs!!.action)
        assertTrue(controller.stop());runCurrent()
        assertNull(controller.state.value.activity)
        val stopped=Json.parseToJsonElement(bridge.published.last()) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonNull,stopped["dgs"])
    }

    @Test fun `routine binds the named answer bank before loading assessment answers`() = runTest {
        var selected: String? = null
        var loaded = false
        val files = object : CourseAnswerBankStore {
            override suspend fun load(): CourseAnswerBank? = error("Must bind the routine's named file")
            override fun forName(name: String): CourseAnswerBankStore {
                selected = name
                return object : CourseAnswerBankStore { override suspend fun load(): CourseAnswerBank? { loaded=true; return null } }
            }
        }
        val controller = PageController(desktop,bridge,MutableStateFlow(AppPreferences()),backgroundScope,history,{currentTime},{"run"},answerBankStore=files)
        runCurrent()
        assertNull(controller.start(CourseAutomationRoutine("r","Named",answerAssessments=true,answerBankFile="fleet.json")))
        runCurrent()
        assertEquals("fleet.json",selected)
        assertTrue(loaded)
        controller.stop(); runCurrent()
    }

    private class FakeBridge : LocalBridge {
        override val connected = MutableStateFlow(true)
        override val problem = MutableStateFlow<String?>(null)
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val incoming: Flow<String> = messages
        val published = mutableListOf<String>()
        override fun publish(stateJson: String) {
            published += stateJson
        }
        override fun close() = Unit
    }

    private class FakeDesktop : DesktopAutomation by UnsupportedDesktopAutomation {
        val keys = MutableSharedFlow<HotKey>(extraBufferCapacity = 8)
        var alerts = 0
        var alertFails = false
        var inputAccess = InputAccess.Granted
        var frontApp: String? = null
        var position: ScreenPoint? = ScreenPoint(100, 100)
        var corners = emptySet<ScreenPoint>()
        val clicks = mutableListOf<ScreenPoint?>()
        val broughtForward = mutableListOf<String>()
        override val pointer = object : PointerDevice {
            override fun position() = this@FakeDesktop.position
            override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
                if (at != null) this@FakeDesktop.position = at
                clicks += at
                return true
            }
            override fun isScreenCorner(point: ScreenPoint) = point in corners
            override fun isOverOwnFocusedWindow(point: ScreenPoint) = false
        }
        override val apps = object : ForegroundApps {
            override fun frontmost(): String? = frontApp
            override fun running(): List<String> = emptyList()
            override fun bringToFront(name: String) {
                broughtForward += name
            }
        }
        override val hotKeys = object : HotKeyMonitor {
            override val presses: Flow<HotKey> = keys
        }
        override val access = object : InputAccessGate {
            override fun access() = inputAccess
            override fun requestAccess() = true
        }
        override fun playAlert() {
            alerts++
            check(!alertFails) { "Test audio output unavailable" }
        }
    }

    private val bridge = FakeBridge()
    private val desktop = FakeDesktop()
    private val history = FakeRunHistory()
    private val json = Json { ignoreUnknownKeys = true }
    private val helper = CourseAutomationRoutine("h", "Course pages")

    private fun TestScope.controller() =
        PageController(desktop, bridge, MutableStateFlow(AppPreferences()), backgroundScope, history, { currentTime }, { "run" }).also { runCurrent() }

    private fun lastCommand() = json.decodeFromString(HelperCommand.serializer(), bridge.published.last())

    @Test
    fun `given a completed module then refresh durable progress before selecting another course`() = runTest {
        var imports = 0
        val profiles = MutableStateFlow(emptyList<com.jagdeep.persistence.room.learning.LearningAccount>())
        val repository = object : CourseProgressRepository {
            override val available = true
            override fun accounts() = profiles
            override suspend fun records(account: String) = emptyList<com.jagdeep.persistence.room.learning.LearningRecord>()
            override suspend fun save(account: com.jagdeep.persistence.room.learning.LearningAccount, records: List<com.jagdeep.persistence.room.learning.LearningRecord>) {
                if(records.isEmpty()) imports++
                profiles.value = listOf(account)
            }
        }
        val controller = PageController(desktop,bridge,MutableStateFlow(AppPreferences()),backgroundScope,history,{currentTime},{"run"},progressRepository=repository)
        runCurrent(); controller.start(CourseAutomationRoutine("course","Course",waitAfterClickMillis=0)); runCurrent()
        val session=lastCommand().courseAutomation!!.session
        var counter=0
        suspend fun send(snapshot: CourseSnapshot) {
            val chunks=json.encodeToString(CourseSnapshot.serializer(),snapshot).chunked(1000)
            val id="snapshot-${++counter}"
            chunks.forEachIndexed { index, chunk ->
                bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(),CourseWireEvent("courseChunk",session,id,index,chunks.size,chunk)))
                runCurrent()
            }
        }
        suspend fun ack() {
            val action=lastCommand().courseAutomation!!.action!!
            bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(),CourseWireEvent("courseAction",session,request=action.request,success=true)))
            runCurrent()
        }
        val links=listOf(CourseElement("home",tag="a",href="/dashboard"),CourseElement("progress",tag="a",href="/dashboard/progress"))
        val dashboard=CourseSnapshot(session,"doc","/dashboard",profileName="Person",elements=links)
        val progress=dashboard.copy(path="/dashboard/progress",progress=ProgressTable(true,page=1,pages=1,total=0,unfiltered=true))
        send(dashboard)
        val profile=(controller.state.value.activity as PageActivity.Automation).progressProfile!!
        controller.confirmProgressProfile(profile.token,"Person")
        send(progress); assertEquals(1,imports)
        send(progress); assertEquals("home",lastCommand().courseAutomation!!.action?.element)
        ack(); advanceTimeBy(1000)
        val finishedLesson = dashboard.copy(path="/dashboard/courses/abc/lesson",elements=links+listOf(
            CourseElement("video",tag="video",ended=true),CourseElement("course-back",tag="a",href="/dashboard/courses/abc",label="Back to Course")))
        send(finishedLesson)
        assertEquals("course-back",lastCommand().courseAutomation!!.action?.element)
        ack(); advanceTimeBy(1000)
        val course=dashboard.copy(path="/dashboard/courses/abc",elements=links+CourseElement("done",label="Rewatch video",context="Part:1 Completed"))
        send(course); advanceTimeBy(2000); send(course)
        assertEquals("home",lastCommand().courseAutomation!!.action?.element)
        ack(); send(dashboard)
        assertEquals("progress",lastCommand().courseAutomation!!.action?.element,"Progress must be checked before another course is opened")
        ack(); advanceTimeBy(1000); send(progress)
        assertEquals(2,imports)
        controller.stop(); runCurrent()
    }

    @Test
    fun `given course automation starts from the app then bring Chrome forward for page updates`() = runTest {
        val controller = controller()
        assertNull(controller.start(CourseAutomationRoutine("course", "Course")))
        assertEquals(listOf("Google Chrome"), desktop.broughtForward)
        controller.stop()
    }

    @Test
    fun `given a failed assessment then use a distinct alert honor mute and allow manual takeover without a retry click`() = runTest {
        val preferences = MutableStateFlow(AppPreferences(assessmentSoundsEnabled = false))
        val kinds = mutableListOf<AssessmentAlertKind>()
        val controller = PageController(desktop, bridge, preferences, backgroundScope, history, { currentTime }, { "run" },
            assessmentAlerts = AssessmentAlerts { kinds += it })
        runCurrent()
        controller.start(CourseAutomationRoutine("auto", "Course"))
        runCurrent()
        val session = lastCommand().courseAutomation!!.session
        val failed = CourseSnapshot(session, "result-doc", "/dashboard/courses/abc/assessment/result/result1", elements = listOf(CourseElement("retry", label = "Re-attempt")),
            assessment = CourseAssessment("Wellness", result = "failed", retry = "retry"))
        suspend fun send(id: String) {
            val text = json.encodeToString(CourseSnapshot.serializer(), failed)
            val chunks = text.chunked(1000)
            chunks.forEachIndexed { index, chunk ->
                bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(), CourseWireEvent("courseChunk", session, id, index, chunks.size, chunk)))
                runCurrent()
            }
        }
        send("first")
        assertTrue(kinds.isEmpty())
        assertNotNull((controller.state.value.activity as PageActivity.Automation).failure)
        preferences.value = preferences.value.copy(assessmentSoundsEnabled = true)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(AssessmentAlertKind.Failure), kinds)
        send("repeat")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, kinds.size, "Failure gets one distinct sound; question reminders alone repeat")
        val token = (controller.state.value.activity as PageActivity.Automation).failure!!.token
        controller.retryAssessment("old-token", automatic = true)
        assertNull(lastCommand().courseAutomation!!.action)
        controller.retryAssessment(token, automatic = false)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertNull(lastCommand().courseAutomation)
    }

    @Test
    fun `given unmatched assessment questions then alert once per question show status and never guess`() = runTest {
        val store = object : CourseAnswerBankStore {
            override suspend fun load() = CourseAnswerBank.decode("""{"Wellness":[{"question":"Known?","options":["A","B"],"answer":"B"}]}""")
        }
        val preferences = MutableStateFlow(AppPreferences())
        val controller = PageController(desktop, bridge, preferences, backgroundScope, history, { currentTime }, { "run" }, answerBankStore = store)
        runCurrent()
        controller.start(CourseAutomationRoutine("auto", "Course", waitAfterClickMillis = 0, answerAssessments = true))
        runCurrent()
        val session = lastCommand().courseAutomation!!.session
        var snapshotNumber = 0
        suspend fun send(snapshot: CourseSnapshot) {
            val encoded = json.encodeToString(CourseSnapshot.serializer(), snapshot)
            val chunks = encoded.chunked(1000)
            val id = "snapshot-${++snapshotNumber}"
            chunks.forEachIndexed { index, chunk ->
                bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(), CourseWireEvent("courseChunk", session, id, index, chunks.size, chunk)))
                runCurrent()
            }
        }
        val course = CourseSnapshot(session, "doc", "/dashboard/courses/abc", elements = listOf(
            CourseElement("title", tag = "a", label = "Wellness", href = "/dashboard/courses/abc"),
            CourseElement("done", label = "Rewatch video", context = "Part:1 Completed"), CourseElement("assessment", label = "Assessment")))
        send(course)
        advanceTimeBy(2000)
        send(course)
        val approval = (controller.state.value.activity as PageActivity.Automation).approval!!
        controller.approveAssessment("stale")
        assertEquals(2, desktop.broughtForward.size)
        controller.approveAssessment(approval.token)
        assertEquals(3, desktop.broughtForward.size, "Return focus to Chrome after app approval")
        send(course)
        val action = lastCommand().courseAutomation!!.action!!
        bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(), CourseWireEvent("courseAction", session, request = action.request, success = true)))
        runCurrent()
        val question = CourseQuestion("q0", "Unknown question?", listOf(CourseOption("a", "A"), CourseOption("b", "B")))
        val test = course.copy(path = "/dashboard/courses/abc/test/test1", assessment = CourseAssessment("Wellness", listOf(question), totalQuestions = 1))
        send(test)
        assertEquals(1, desktop.alerts)
        assertTrue((controller.state.value.activity as PageActivity.Automation).detail.contains("Unknown question?"))
        assertNull(lastCommand().courseAutomation!!.action)
        send(test)
        assertEquals(1, desktop.alerts, "Repeated inventories must not beep continuously")
        advanceTimeBy(6000)
        runCurrent()
        assertTrue((controller.state.value.activity as PageActivity.Automation).detail.contains("Unknown question?"), "A missing page update must not hide an unanswered-question warning")
        send(test.copy(assessment = test.assessment!!.copy(questions = listOf(question.copy(id = "rerendered", options = question.options.reversed())))))
        assertEquals(1, desktop.alerts, "Recreated controls and reordered options are still the same unanswered question")
        advanceTimeBy(23_999)
        runCurrent()
        assertEquals(1, desktop.alerts, "No reminder before 30 seconds")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, desktop.alerts, "Reminder at 30 seconds without a new inventory")
        preferences.value = preferences.value.copy(assessmentSoundsEnabled = false)
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, desktop.alerts, "Muted settings suppress reminders")
        preferences.value = preferences.value.copy(assessmentSoundsEnabled = true)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(3, desktop.alerts)
        send(test.copy(assessment = test.assessment!!.copy(questions = listOf(question.copy(id = "q1", text = "Another unknown?")))))
        assertEquals(4, desktop.alerts)
        desktop.alertFails = true
        val audioFailure = test.copy(assessment = test.assessment.copy(questions = listOf(question.copy(id = "q2", text = "Needs another answer?"))))
        send(audioFailure)
        assertEquals(5, desktop.alerts)
        assertTrue((controller.state.value.activity as PageActivity.Automation).detail.contains("Sound unavailable"))
        send(audioFailure)
        assertEquals(5, desktop.alerts)
        assertTrue((controller.state.value.activity as PageActivity.Automation).detail.contains("Sound unavailable"))
        controller.stop()
        runCurrent()
        send(test)
        assertEquals(5, desktop.alerts, "Stopped sessions cannot trigger alerts")
    }

    @Test
    fun `given course automation when a snapshot arrives then it commands a target and stop immediately revokes the session`() = runTest {
        val controller = controller()
        assertNull(controller.start(CourseAutomationRoutine("auto", "Automatic course", playbackSpeed = 2.0)))
        runCurrent()
        val session = lastCommand().courseAutomation!!.session
        advanceTimeBy(5100)
        runCurrent()
        assertTrue((controller.state.value.activity as PageActivity.Automation).detail.contains("Waiting for page updates"))
        assertEquals(2.0, lastCommand().courseAutomation!!.playbackSpeed)
        assertFalse(lastCommand().course)
        assertFalse(lastCommand().autoRun)
        val snapshot = CourseSnapshot(session, "doc", "/dashboard/courses/abc", elements = listOf(CourseElement("watch", label = "Watch video", context = "Part 1 Not started")))
        val encoded = json.encodeToString(CourseSnapshot.serializer(), snapshot)
        encoded.chunked(1000).forEachIndexed { index, chunk ->
            bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(), CourseWireEvent("courseChunk", session, "snapshot", index, encoded.chunked(1000).size, chunk)))
            runCurrent()
        }
        assertEquals("watch", lastCommand().courseAutomation?.action?.element)
        assertFalse((controller.state.value.activity as PageActivity.Automation).detail.contains("Waiting for page updates"))
        bridge.messages.emit(json.encodeToString(CourseWireEvent.serializer(), CourseWireEvent("courseAction", session, request = lastCommand().courseAutomation!!.action!!.request, success = true)))
        runCurrent()
        assertTrue(controller.stop())
        assertNull(lastCommand().courseAutomation)
        runCurrent()
        assertFalse(lastCommand().enabled)
        assertEquals(1, history.records.value.single().clicks)
    }

    private fun TestScope.found(label: String?) {
        val body = if (label == null) """{"type":"found","label":null}""" else """{"type":"found","label":"$label"}"""
        bridge.messages.tryEmit(body)
        runCurrent()
    }

    private fun TestScope.press(key: HotKey) {
        desktop.keys.tryEmit(key)
        runCurrent()
    }

    private fun TestScope.page(atBottom: Boolean, signature: String? = "a", focused: Boolean = true, video: String = "none") {
        val quoted = if (signature == null) "null" else "\"$signature\""
        bridge.messages.tryEmit("""{"type":"page","focused":$focused,"atBottom":$atBottom,"signature":$quoted,"video":"$video"}""")
        runCurrent()
    }

    private fun scrollRoutine(rounds: Int = 3, watchVideos: Boolean = true) =
        ScrollClickRoutine("s", "Next pages", ClickPoint(SPOT.x, SPOT.y), rounds = rounds, watchVideos = watchVideos)

    private fun TestScope.startedRun(rounds: Int = 3): PageController {
        val controller = controller()
        assertNull(controller.start(scrollRoutine(rounds)))
        runCurrent()
        return controller
    }

    private fun TestScope.finish(controller: PageController) {
        controller.stop()
        runCurrent()
    }

    private class RecordingLog : com.jagdeep.shared.live.LiveLog {
        override val entries = MutableStateFlow<List<com.jagdeep.shared.live.LiveEntry>>(emptyList())
        override val isRecording = true
        override fun add(source: com.jagdeep.shared.live.LiveSource, text: String) {
            entries.value = entries.value + com.jagdeep.shared.live.LiveEntry(entries.value.size.toLong(), 0, source, text)
        }
        override fun clear() = Unit
    }

    @Test
    fun `given developer mode when automation runs then the log shows commands and messages`() = runTest {
        val live = RecordingLog()
        val controller = PageController(desktop, bridge, MutableStateFlow(AppPreferences()), backgroundScope, history, { currentTime }, { "run" }, live)
        runCurrent()
        controller.start(helper)
        found("Next")
        press(HotKey.F8)
        finish(controller)
        val texts = live.entries.value.map { it.text }
        assertTrue(texts.any { it.startsWith("→ ") && "\"enabled\":true" in it }, "The command sent to Chrome")
        assertTrue(texts.any { it == "← {\"type\":\"found\",\"label\":\"Next\"}" }, "The message heard from Chrome")
        assertTrue("Started Course pages (Sagar Mein Yog automation)" in texts)
        assertEquals(0, lastCommand().advance, "Removed helper key must never click")
        assertTrue(texts.any { it.startsWith("Ended Course pages") })
    }

    // Nothing running

    @Test
    fun `given no routine running when launched then the extension is told to do nothing`() = runTest {
        controller()
        assertFalse(lastCommand().enabled)
    }

    @Test
    fun `given no routine running when the page keys are pressed then nothing happens`() = runTest {
        val controller = controller()
        found("Next")
        press(HotKey.F8)
        press(HotKey.F9)
        assertEquals(0, lastCommand().advance)
        assertFalse(controller.state.value.isScrolling)
    }

    @Test
    fun `given Chrome isn't connected when a routine is started then it explains and doesn't start`() = runTest {
        bridge.connected.value = false
        val controller = controller()
        assertNotNull(controller.start(helper))
        assertNotNull(controller.start(scrollRoutine()))
        assertNull(controller.state.value.activity)
    }

    @Test
    fun `given the link couldn't start when a routine is started then that's the reason given`() = runTest {
        bridge.problem.value = "Port 47613 is in use by another app."
        val controller = controller()
        assertEquals("Port 47613 is in use by another app.", controller.start(helper))
        assertNull(controller.state.value.activity)
    }

    @Test
    fun `given no input permission when started then scroll and click refuses but the page helper doesn't need it`() = runTest {
        desktop.inputAccess = InputAccess.NeedsPermission
        val controller = controller()
        assertNotNull(controller.start(scrollRoutine()))
        assertNull(controller.start(helper))
        finish(controller)
    }

    @Test
    fun `given a routine running when another is started then it's ignored`() = runTest {
        val controller = controller()
        controller.start(helper)
        controller.start(scrollRoutine())
        runCurrent()
        assertTrue(controller.state.value.activity is PageActivity.Automation)
        finish(controller)
    }

    // Page helper routine






    @Test
    fun `given a lost connection when shown then nothing counts as highlighted`() = runTest {
        val controller = controller()
        found("Next")
        assertEquals("Next", controller.state.value.foundLabel)
        bridge.connected.value = false
        runCurrent()
        assertNull(controller.state.value.foundLabel)
    }


    @Test
    fun `given malformed or unknown messages when received then they're ignored`() = runTest {
        val controller = controller()
        listOf("not json", "{}", """{"type":"other"}""").forEach { bridge.messages.tryEmit(it) }
        runCurrent()
        assertNull(controller.state.value.foundLabel)
        assertEquals(0, desktop.alerts)
    }

    // Course helper routine



    @Test
    fun `given a run starts when no page has reported then Chrome is brought forward and it's reading the page`() = runTest {
        val controller = startedRun()
        advanceTimeBy(1_000)
        assertEquals(listOf(BROWSER_APP_NAME), desktop.broughtForward)
        assertEquals(ScrollClickPhase.ReadingPage, (controller.state.value.activity as PageActivity.ScrollClick).progress.phase)
        assertTrue(desktop.clicks.isEmpty())
        finish(controller)
    }

    @Test
    fun `given a run when started then it scrolls and tells the extension, and doesn't click before the bottom`() = runTest {
        val controller = startedRun()
        assertTrue(lastCommand().scrolling)
        assertTrue(lastCommand().autoRun)
        assertTrue(lastCommand().watchVideos)
        page(atBottom = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
        finish(controller)
    }

    @Test
    fun `given the bottom when it holds then one click lands on the spot and scrolling pauses`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(1_000)
        assertTrue(desktop.clicks.isEmpty())
        advanceTimeBy(1_200)
        assertEquals(listOf<ScreenPoint?>(SPOT), desktop.clicks)
        assertFalse(lastCommand().scrolling)
        finish(controller)
    }

    @Test
    fun `given the page changes after each click when rounds run out then it finishes and records the run by name`() = runTest {
        val controller = startedRun(rounds = 2)
        page(atBottom = true, signature = "one")
        advanceTimeBy(2_200)
        page(atBottom = false, signature = null)
        advanceTimeBy(200)
        assertTrue(lastCommand().scrolling, "It scrolls again on the new page")
        page(atBottom = true, signature = "two")
        advanceTimeBy(2_200)
        runCurrent()

        assertEquals(2, desktop.clicks.size)
        assertNull(controller.state.value.activity)
        assertFalse(lastCommand().autoRun)
        val record = history.added.single()
        assertEquals(RunOutcome.Completed, record.outcome)
        assertEquals("Next pages", record.routineName)
    }

    @Test
    fun `given several steps when the bottom is reached then they're clicked in order, each after its wait and with its button`() = runTest {
        val routine = ScrollClickRoutine(
            "s", "Next pages", rounds = 1,
            points = listOf(
                ClickPoint(1, 1, 0, PointerButton.Left, "Play"),
                ClickPoint(2, 2, 1_000, PointerButton.Right, "Next"),
            ),
        )
        val buttons = mutableListOf<PointerButton>()
        val controller = PageController(
            object : DesktopAutomation by desktop {
                override val pointer = object : PointerDevice by desktop.pointer {
                    override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
                        buttons += button
                        return desktop.pointer.click(at, button, count)
                    }
                }
            },
            bridge, MutableStateFlow(AppPreferences()), backgroundScope, history, { currentTime }, { "run" },
        )
        runCurrent()
        controller.start(routine)
        runCurrent()
        page(atBottom = true)
        advanceTimeBy(2_200)
        assertEquals(listOf<ScreenPoint?>(ScreenPoint(1, 1)), desktop.clicks, "The first step clicks once the page settles")
        advanceTimeBy(500)
        assertEquals(1, desktop.clicks.size, "The second waits its second")
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf<ScreenPoint?>(ScreenPoint(1, 1), ScreenPoint(2, 2)), desktop.clicks)
        assertEquals(listOf(PointerButton.Left, PointerButton.Right), buttons)
        assertEquals(RunOutcome.Completed, history.added.single().outcome)
    }

    @Test
    fun `given a button step when its button is clicked in Chrome then the run goes on without moving the pointer`() = runTest {
        val routine = ScrollClickRoutine(
            "s", "Lessons", rounds = 1,
            points = listOf(ClickPoint(0, 0, 0, null, "Back", text = "Back to Course")),
        )
        val controller = controller()
        controller.start(routine)
        runCurrent()
        page(atBottom = true)
        advanceTimeBy(2_200)
        assertEquals("Back to Course", lastCommand().clickText)
        val request = lastCommand().clickRequest
        assertTrue(request > 0)
        assertTrue(desktop.clicks.isEmpty(), "A button step doesn't click through the pointer")

        bridge.messages.tryEmit("""{"type":"textClicked","request":$request,"label":"Back to Course"}""")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(null, lastCommand().clickText, "Asked once, then cleared")
        assertEquals(RunOutcome.Completed, history.added.single().outcome)
        assertEquals(1, history.added.single().clicks)
    }

    @Test
    fun `given a button that never appears when waited for then the run stops and says so`() = runTest {
        val routine = ScrollClickRoutine("s", "Lessons", rounds = 1, points = listOf(ClickPoint(0, 0, 0, null, "Back", text = "Back to Course")))
        val controller = controller()
        controller.start(routine)
        runCurrent()
        page(atBottom = true)
        advanceTimeBy(2_200 + 30_100)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.ButtonNotFound, history.added.single().outcome)
        assertEquals(null, lastCommand().clickText)
    }

    @Test
    fun `given a click report for an older request when heard then it isn't taken for the current one`() = runTest {
        val routine = ScrollClickRoutine("s", "Lessons", rounds = 1, points = listOf(ClickPoint(0, 0, 0, null, "Back", text = "Back to Course")))
        val controller = controller()
        controller.start(routine)
        runCurrent()
        page(atBottom = true)
        advanceTimeBy(2_200)
        bridge.messages.tryEmit("""{"type":"textClicked","request":0,"label":"Back to Course"}""")
        advanceTimeBy(1_000)
        assertTrue(controller.state.value.activity != null, "Still waiting for its own request")
        finish(controller)
    }

    @Test
    fun `given a click that changes nothing when time passes then it stops instead of clicking again`() = runTest {
        val controller = startedRun()
        page(atBottom = true, signature = "same")
        advanceTimeBy(18_200)
        runCurrent()
        assertEquals(1, desktop.clicks.size)
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.PageUnchanged, history.added.single().outcome)
    }

    @Test
    fun `given another app in front when at the bottom then it never clicks, and goes on when Chrome returns`() = runTest {
        val controller = startedRun()
        page(atBottom = true, focused = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
        page(atBottom = true, focused = true)
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)
        finish(controller)
    }

    @Test
    fun `given a video playing at the bottom then it never clicks until the video ends`() = runTest {
        val controller = startedRun()
        page(atBottom = true, video = "playing")
        advanceTimeBy(600_000)
        assertTrue(desktop.clicks.isEmpty())
        page(atBottom = true)
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)
        finish(controller)
    }

    @Test
    fun `given the pointer moved by hand after a click when checked then the run stops`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(2_200)
        desktop.position = ScreenPoint(SPOT.x + 50, SPOT.y)
        advanceTimeBy(100)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.PointerMoved, history.added.single().outcome)
    }

    @Test
    fun `given the pointer in a screen corner when checked then the run stops before any click`() = runTest {
        val controller = startedRun()
        desktop.corners = setOf(ScreenPoint(0, 0))
        desktop.position = ScreenPoint(0, 0)
        page(atBottom = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(desktop.clicks.isEmpty())
        assertEquals(RunOutcome.ScreenCorner, history.added.single().outcome)
    }

    @Test
    fun `given the scroll key during a run when pressed then the run stops`() = runTest {
        val controller = startedRun()
        press(HotKey.F9)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertFalse(lastCommand().scrolling)
        assertEquals(RunOutcome.StoppedByUser, history.added.single().outcome)
    }

    @Test
    fun `given a run when asked to stop then it reports there was one, and afterwards that there isn't`() = runTest {
        val controller = startedRun()
        assertTrue(controller.stop())
        runCurrent()
        assertFalse(controller.stop())
    }

    @Test
    fun `given Chrome disconnects during a run when checked then the run stops and is recorded`() = runTest {
        val controller = startedRun()
        bridge.connected.value = false
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.BrowserDisconnected, history.added.single().outcome)
    }

    @Test
    fun `given a scroll and click run when the click key is pressed then it doesn't click a highlighted button`() = runTest {
        val controller = startedRun()
        found("Next")
        press(HotKey.F8)
        assertEquals(0, lastCommand().advance)
        finish(controller)
    }
}
