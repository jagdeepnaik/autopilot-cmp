package com.jagdeep.autopilot

import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.home.domain.CourseAutomationRoutine
import com.jagdeep.home.domain.CourseAnswerBank
import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.PageController
import com.jagdeep.localbridge.createLocalBridge
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.preferences.AppPreferences
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Real Kotlin runner + real extension JavaScript + headless Chrome, isolated from the user's tabs/data. */
class LiveCourseAutomationTest {
    private fun bundled(name: String) = checkNotNull(javaClass.getResourceAsStream("/chrome-extension/$name")).bufferedReader().use { it.readText() }
    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out: $description" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `given dashboard course and video in Chrome when automation runs then it watches and returns without touching quizzes`() = verifyFlow(spa = false)

    @Test
    fun `given client side navigation with a stale sender URL in Chrome then automation watches the lesson and returns`() = verifyFlow(spa = true)

    @Test
    fun `given two unfinished videos and focus in the Chrome address bar then each video finishes before the next opens`() =
        verifyFlow(spa = true, multiple = true, pageFocused = false)

    @Test
    fun `given dashboard header menus and two videos at double speed then completed player replacement advances with Next`() =
        verifyFlow(spa = true, multiple = true, directNext = true)

    @Test
    fun `given local assessment answers then all videos finish before selecting submitting and returning to dashboard`() =
        verifyFlow(spa = true, multiple = true, directNext = true, assessmentMode = true)

    @Test
    fun `given the observed live test route and div question layout then the app answers only after approvals`() =
        verifyFlow(spa = true, multiple = true, directNext = true, assessmentMode = true, liveLayout = true)

    @Test
    fun `given an unknown live layout question then the app alerts and pauses without selecting or submitting`() =
        verifyFlow(spa = true, multiple = true, directNext = true, assessmentMode = true, liveLayout = true, unknownAnswer = true, manualStart = true)

    @Test
    fun `given a live NOT CLEARED result then the app reports failure without retrying`() =
        verifyFlow(spa = true, multiple = true, directNext = true, assessmentMode = true, liveLayout = true, failedResult = true)

    private fun verifyFlow(spa: Boolean, multiple: Boolean = false, pageFocused: Boolean = true, directNext: Boolean = false, assessmentMode: Boolean = false, liveLayout: Boolean = false, unknownAnswer: Boolean = false, manualStart: Boolean = false, failedResult: Boolean = false) {
        val chrome = System.getenv("AUTOPILOT_LIVE_CHROME_TEST") ?: return
        val reports = ConcurrentLinkedQueue<String>()
        val scripts = """
            <script src="/stub.js"></script><script src="/background.js"></script>
            <script src="/course-automation-background.js"></script>
            <script src="/content.js"></script><script src="/course-automation.js"></script>
        """.trimIndent()
        val dashboard = """
            <button aria-expanded="false" onclick="report('wrong-header')">Profile</button>
            <h2>All Courses</h2>
            <section><h3>Finished course</h3><span>Completed Min. Pass 75% Duration 20min</span><button onclick="report('wrong-course')"><svg width="20" height="20"></svg></button></section>
            <section><h3>Wellness</h3><span id="status">Min. Pass 75% Duration 71min</span><a href="https://sagarmeinyog.com/dashboard/courses/abc" onclick="event.preventDefault(); report('open-course'); location.href='/dashboard/courses/abc'">Open course</a></section>
            <script>if(localStorage.getItem('finished')) document.getElementById('status').prepend('Completed ');</script>
        """.trimIndent()
        val singleCourse = """
            <h1>Wellness course</h1><a href="/dashboard" onclick="report('dashboard')">Back to Dashboard</a>
            <section><span>Part 1</span><span id="status">Not started</span><button onclick="report('lesson'); location.href='/dashboard/courses/abc/def'">Watch video</button></section>
            <button onclick="report('quiz')">Assessment</button>
            <script>if(localStorage.getItem('finished')) document.getElementById('status').textContent='Completed';</script>
        """.trimIndent()
        val singleLesson = """
            <h1>Part 1</h1><video id="video" style="width:300px;height:150px"></video>
            <button onclick="report('quiz')">Skip Quiz</button><button onclick="report('back'); location.href='/dashboard/courses/abc'">Back to Course</button>
            <input type="password" value="never-report-this-password">
            <script>
              const video = document.getElementById('video'); let paused = true, ended = false;
              Object.defineProperty(video, 'paused', {get: () => paused}); Object.defineProperty(video, 'ended', {get: () => ended});
              video.play = () => {report('play'); paused=false; setTimeout(() => {ended=true; paused=true; localStorage.setItem('finished','yes'); report('ended');}, 1200); return Promise.resolve();};
            </script>
        """.trimIndent()
        val assessmentPath = if (liveLayout) "/dashboard/courses/abc/test/attempt1" else "/dashboard/courses/abc/assessment"
        val course = (if (!multiple) singleCourse else """
            <h1>Wellness course</h1><a href="/dashboard" onclick="report('dashboard')">Back to Dashboard</a>
            <table>
              <tr><td>1.1 Part:1</td><td id="status1">Not Started</td><td><button id="watch1" onclick="report('lesson'); location.href='/dashboard/courses/abc/def'">Watch video</button></td></tr>
              <tr><td>1.2 Part:2</td><td id="status2">Not Started</td><td><button id="watch2" onclick="report('lesson2'); location.href='/dashboard/courses/abc/ghi'">Watch video</button></td></tr>
            </table><button onclick="report('quiz')">Assessment</button>
            <script>for(const n of [1,2]) if(localStorage.getItem('finished'+n)) { document.getElementById('status'+n).textContent='Completed'; document.getElementById('watch'+n).textContent='Rewatch video'; }</script>
        """.trimIndent()).let {
            if (!assessmentMode) it
            else if (!manualStart) it.replace("report('quiz')", "report('assessment'); location.href='$assessmentPath'")
            else it.replace("report('quiz')", "report('assessment'); document.getElementById('manual-start').hidden=false") + """
                <main id="manual-start" hidden><a href="/dashboard/courses/abc">Wellness</a>
                <h2>Course Not Fully Viewed</h2><p>You can still start the assessment if you're ready.</p>
                <button onclick="report('started'); location.href='$assessmentPath'">Start Assessment</button></main>
            """.trimIndent()
        }
        val assessmentPage = (if (liveLayout) """
            <main><div>Wellness Assessment</div><a href="/dashboard" onclick="report('dashboard')">Back to Dashboard</a>
            <form id="assessment-form" onsubmit="event.preventDefault(); document.getElementById('confirm-submit').hidden=false">
            <div><div>1 out of 1</div><div>Question 1</div><div>Which option is correct?</div><div>(Select one answer)</div><div>
            <label><input type="radio" name="q0" onclick="report('answer-a')"><span>First</span></label>
            <label><input type="radio" name="q0" onclick="report('answer-b')"><span>Second</span></label></div></div>
            <button type="submit">Submit</button></form>
            <div id="confirm-submit" hidden><div>End Test?</div><p>Your answers will be submitted. Are you sure you want to end the test?</p>
            <button type="button">Cancel</button><button type="button" onclick="report('submitted'); location.href='/dashboard/courses/abc/assessment/result/result1'">End Test</button></div></main>
        """.trimIndent() else """
            <main><a href="/dashboard/courses/abc">Wellness</a><a href="/dashboard" onclick="report('dashboard')">Back to Dashboard</a>
            <h2>Assessment</h2><form id="assessment-form" onsubmit="event.preventDefault(); report('submitted'); this.innerHTML='<h2>Assessment Passed</h2>'">
            <fieldset><legend>Which option is correct?</legend>
            <label><input type="radio" name="question" onclick="report('answer-a')">1. First</label>
            <label><input type="radio" name="question" onclick="report('answer-b')">2. Second</label></fieldset>
            <button type="submit">Submit Assessment</button></form></main>
        """.trimIndent())
        fun endScreen(page: String) = if (!directNext) page else page.replace("Part 1", "Part:1").replace("Part 2", "Part:2")
            .replace("report('play');", "report('play'); report('speed:'+video.playbackRate);")
            .replace("report('play2');", "report('play2'); report('speed2:'+video.playbackRate);")
            .replace("report('ended');", "report('ended'); video.insertAdjacentHTML('afterend','<h2>Video Completed!</h2>'); video.remove();")
            .replace("report('ended2');", "report('ended2'); video.insertAdjacentHTML('afterend','<h2>Video Completed!</h2>'); video.remove();")
        val lesson = endScreen(if (multiple) singleLesson.replace("localStorage.setItem('finished','yes')", "localStorage.setItem('finished1','yes')") else singleLesson)
            .let { if (!directNext) it else it.replace("report('back'); location.href='/dashboard/courses/abc'", "report('next'); location.href='/dashboard/courses/abc/ghi'").replace(">Back to Course</button>", ">Next</button>") }
        val secondLesson = endScreen(singleLesson.replace("Part 1", "Part 2")
            .replace("localStorage.setItem('finished','yes')", "localStorage.setItem('finished2','yes'); localStorage.setItem('finished','yes')")
            .replace("report('play')", "report('play2')").replace("report('ended')", "report('ended2')").replace("report('back')", "report('back2')"))
        val stub = """
            const listeners = [];
            Object.defineProperty(document, 'hasFocus', {value: () => $pageFocused});
            window.chrome = {
              runtime: {id:'test', getPlatformInfo() {}, onMessage:{addListener(l){listeners.push(l)}}, onStartup:{addListener(){}}, onInstalled:{addListener(){}},
                sendMessage(message){return new Promise(resolve => {let waiting=false; const sender={documentId:'fixture-document',tab:{id:1,url:'https://sagarmeinyog.com'+location.pathname},frameId:0,url:'https://sagarmeinyog.com'+${if (spa) "'/dashboard'" else "location.pathname"}}; for(const l of listeners) waiting=l(message,sender,resolve)===true || waiting; if(!waiting) resolve();});}},
              tabs:{query:async()=>[{id:1}],sendMessage:async()=>null},
              windows:{getLastFocused:async()=>({focused:true}),onFocusChanged:{addListener(){}}},alarms:{create(){},onAlarm:{addListener(){}}}
            };
        """.trimIndent()
        fun clientPage(page: String) = Regex("location.href='([^']+)'").replace(page) { "go('${it.groupValues[1]}')" }
            .replace("report('dashboard')", "event.preventDefault(); report('dashboard'); go('/dashboard')")
        val clientPages = buildJsonObject {
            put("/dashboard", clientPage(dashboard).let { if (assessmentMode) it.replace("https://sagarmeinyog.com/dashboard/courses/abc", "/dashboard/courses/abc") else it })
            put("/dashboard/courses/abc", clientPage(course))
            put("/dashboard/courses/abc/def", clientPage(lesson))
            put("/dashboard/courses/abc/ghi", clientPage(secondLesson))
            put(assessmentPath, clientPage(assessmentPage))
            put("/dashboard/courses/abc/assessment/result/result1", clientPage(if (failedResult)
                """<main><a href="/dashboard/courses/abc">Wellness</a><div>You've scored</div><div>70.00%</div><div>NOT CLEARED</div><p>You need to score higher than 75% to pass this course.</p><button onclick="report('retake')">Re-attempt</button><button onclick="report('dashboard'); location.href='/dashboard'">Back home</button></main>"""
                else """<main><a href="/dashboard/courses/abc">Wellness</a><div>You've scored</div><div>100.00%</div><div>CLEARED</div><p>Well done — you've successfully passed with a score above 75%!</p><button onclick="report('dashboard'); location.href='/dashboard'">Back home</button></main>"""))
        }
        val clientShell = """
            <div id="fixture"></div><script>
              const fixturePages = ${clientPages.toString().replace("<", "\\u003c")};
              function go(path) { history.pushState({}, '', path); renderFixture(); }
              function renderFixture() {
                const root = document.getElementById('fixture'); root.innerHTML = fixturePages[location.pathname];
                for(const script of root.querySelectorAll('script')) new Function(script.textContent)();
              }
              renderFixture();
            </script>
        """.trimIndent()
        val linkPort = ServerSocket(0).use { it.localPort }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/report") {
                reports += exchange.requestURI.query.substringAfter('=')
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            } else {
                val body = when {
                    path == "/stub.js" -> stub
                    path.endsWith(".js") -> bundled(path.removePrefix("/")).replace("http://127.0.0.1:$BROWSER_LINK_PORT", "http://127.0.0.1:$linkPort")
                        .let { if (assessmentMode && path == "/course-automation.js") it.replace("'https://sagarmeinyog.com'", "location.origin") else it }
                    else -> ("<!doctype html><html><body><script>function report(value){fetch('/report?value='+value)}</script>" + if (spa) clientShell + scripts + "</body></html>" else when (path) {
                        "/dashboard/courses/abc/def" -> lesson
                        "/dashboard/courses/abc/ghi" -> secondLesson
                        "/dashboard/courses/abc" -> course
                        else -> dashboard
                    } + scripts + "</body></html>").replace("report(", "notifyCourseTest(")
                }.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", if (path.endsWith(".js")) "text/javascript" else "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val bridge = createLocalBridge(linkPort, "http://127.0.0.1:${server.address.port}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = ConcurrentLinkedQueue<String>()
        scope.launch { bridge.incoming.collect { received += it } }
        val history = object : RunHistoryStore {
            override val records = MutableStateFlow<List<RunRecord>>(emptyList())
            override suspend fun add(record: RunRecord) { records.value += record }
            override suspend fun clear() { records.value = emptyList() }
        }
        val bankStore = object : CourseAnswerBankStore {
            override suspend fun load() = CourseAnswerBank.decode("""{"Wellness":[{"question":"${if (unknownAnswer) "A different question?" else "Which option is correct?"}","options":["1. First","2. Second"],"answer":"2. Second"}]}""")
        }
        val alerts = java.util.concurrent.atomic.AtomicInteger()
        val automation = object : com.jagdeep.automation.DesktopAutomation by UnsupportedDesktopAutomation {
            override fun playAlert() { alerts.incrementAndGet() }
        }
        val controller = PageController(automation, bridge, MutableStateFlow(AppPreferences()), scope, history, System::currentTimeMillis, { "test" }, answerBankStore = bankStore)
        val profile = Files.createTempDirectory("course-automation-test").toFile()
        val process = ProcessBuilder(chrome, "--headless=new", "--no-first-run", "--user-data-dir=${profile.absolutePath}", "http://127.0.0.1:${server.address.port}/dashboard")
            .redirectErrorStream(true).redirectOutput(File(profile, "chrome.log")).start()
        try {
            waitUntil("extension connected") { controller.state.value.isConnected }
            assertNull(controller.start(CourseAutomationRoutine("new", "Course automation", waitAfterClickMillis = if (multiple) 0 else 5000, playbackSpeed = if (directNext) 2.0 else 1.0, answerAssessments = assessmentMode)))
            if (unknownAnswer) {
                waitUntil("unknown question alert") {
                    (controller.state.value.activity as? com.jagdeep.home.domain.PageActivity.Automation)?.approval?.let { controller.approveAssessment(it.token) }
                    alerts.get() > 0
                }
                assertEquals(1, alerts.get())
                assertTrue((controller.state.value.activity as com.jagdeep.home.domain.PageActivity.Automation).detail.contains("Which option is correct?"))
                assertFalse(reports.any { it.startsWith("answer-") || it == "submitted" })
                return
            }
            if (failedResult) {
                waitUntil("explicit failed result") {
                    (controller.state.value.activity as? com.jagdeep.home.domain.PageActivity.Automation)?.approval?.let { controller.approveAssessment(it.token) }
                    (controller.state.value.activity as? com.jagdeep.home.domain.PageActivity.Automation)?.detail?.contains("did not pass") == true
                }
                assertEquals(1, reports.count { it == "submitted" })
                assertFalse("retake" in reports || "dashboard" in reports)
                assertTrue(history.records.value.isEmpty())
                return
            }
            // These approvals apply only to the isolated fixture, never the user's account.
            waitUntil("automation completed; reports=$reports") {
                (controller.state.value.activity as? com.jagdeep.home.domain.PageActivity.Automation)?.approval?.let {
                    controller.approveAssessment(it.token)
                }
                history.records.value.isNotEmpty()
            }
            assertEquals(RunOutcome.Completed, history.records.value.single().outcome)
            val expected = if (assessmentMode) listOf("open-course", "lesson", "play", "speed:2", "ended", "next", "play2", "speed2:2", "ended2", "back2", "assessment", "answer-b", "submitted", "dashboard")
                else if (directNext) listOf("open-course", "lesson", "play", "speed:2", "ended", "next", "play2", "speed2:2", "ended2", "back2", "dashboard")
                else if (multiple) listOf("open-course", "lesson", "play", "ended", "back", "lesson2", "play2", "ended2", "back2", "dashboard")
                else listOf("open-course", "lesson", "play", "ended", "back", "dashboard")
            // A rate can be applied on an earlier inventory tick, before play is requested.
            assertEquals(expected.filterNot { it.startsWith("speed") }, reports.filterNot { it.startsWith("speed") }, "History: ${history.records.value}; received: ${received.joinToString("\n")}")
            assertEquals(expected.filter { it.startsWith("speed") }.sorted(), reports.filter { it.startsWith("speed") }.sorted())
            assertEquals(if (liveLayout) 10 else if (assessmentMode) 8 else if (directNext) 5 else if (multiple) 6 else 4, history.records.value.single().clicks)
            assertFalse("quiz" in reports || "wrong-course" in reports || "wrong-header" in reports)
            assertFalse(received.any { "never-report-this-password" in it })
        } catch (error: Throwable) {
            throw AssertionError("State: ${controller.state.value}; reports: ${reports.toList()}; recent messages: ${received.toList().takeLast(8)}; Chrome: ${File(profile, "chrome.log").readText().takeLast(2000)}", error)
        } finally {
            controller.stop()
            process.destroyForcibly().waitFor()
            scope.cancel()
            bridge.close()
            server.stop(0)
            profile.deleteRecursively()
        }
    }
}
