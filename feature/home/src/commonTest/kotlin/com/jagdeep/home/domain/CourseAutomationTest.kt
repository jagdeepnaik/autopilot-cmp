package com.jagdeep.home.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CourseAutomationTest {
    private val course = "/dashboard/courses/cmejm5yu00015kt0o5g0p8rzv"
    private val lesson = "$course/cmemys49w00f7ma0owzzxt2qa"
    private fun snapshot(path: String, vararg elements: CourseElement, document: String = path) =
        CourseSnapshot("session", document, path, elements = elements.toList())
    private fun CourseAutomationSession.ack(success: Boolean = true) = acknowledge(action!!.request, success)

    @Test
    fun `given saved assessment pass then still inspect module videos before considering completion`() {
        val runner = CourseAutomationSession("session")
        runner.configureProgress(CourseProgressSummary("user", "User", 1, listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user","old","Passed course","assessment","Course Assessment","passed",90.0,observedAt=1),
            com.jagdeep.persistence.room.learning.LearningRecord("user","new","Passed course","assessment","Course Assessment","unsubmitted",observedAt=2))))
        val passed = CourseElement("passed", svg = true, context = "Passed course Min. Pass 75% Duration 20min", courseTitle = "Passed course")
        val next = CourseElement("next", svg = true, context = "Next course Min. Pass 75% Duration 20min", courseTitle = "Next course")
        runner.read(snapshot("/dashboard",passed,next),0)
        assertEquals("passed",runner.action?.element)
    }

    @Test
    fun `given dashboard load error then retry at intervals until courses return`() {
        val runner = CourseAutomationSession("session", 5000)
        val error = snapshot("/dashboard", CourseElement("all", tag = "h2", label = "All Courses"), CourseElement("retry", label = "Try again"))
        runner.read(error, 0)
        assertEquals("retry", runner.action?.element)
        runner.ack()
        runner.read(error, 9999)
        assertNull(runner.action)
        runner.read(error, 10000)
        assertEquals("retry", runner.action?.element, "The same button can retry after the interval")
        runner.ack()
        runner.read(error.copy(elements = error.elements.map { if (it.id == "retry") it.copy(enabled = false) else it }), 20000)
        assertNull(runner.action)
        val recovered = snapshot("/dashboard", CourseElement("all", tag = "h2", label = "All Courses"),
            CourseElement("course", svg = true, context = "Min. Pass 75% Duration 20min"))
        runner.read(recovered, 21000)
        assertEquals("course", runner.action?.element)
        assertFalse(runner.completed)
    }

    @Test
    fun `given finished dashboard page with more modules then next page is opened instead of stopping`() {
        val runner = CourseAutomationSession("session")
        val done = CourseElement("done-card", svg = true, context = "Completed Min. Pass 75% Duration 20min")
        val next = CourseElement("next-page", label = "Next")
        runner.read(snapshot("/dashboard", done, next, document = "spa"), 0)
        runner.read(snapshot("/dashboard", done, next, document = "spa"), 2000)
        assertEquals("next-page", runner.action?.element)
        assertFalse(runner.completed)
        runner.ack()
        runner.read(snapshot("/dashboard", CourseElement("another-module", svg = true, context = "Min. Pass 75% Duration 35min"), next.copy(enabled = false), document = "spa"), 3000)
        assertEquals("another-module", runner.action?.element)
    }

    @Test
    fun `given split Material s chapter labels then finish every chapter before returning to dashboard and start the next module`() {
        val runner = CourseAutomationSession("session")
        val other = "/dashboard/courses/other"
        val dashboard = arrayOf(CourseElement("all", tag = "h2", label = "All Courses"),
            CourseElement("module1", svg = true, context = "75 % Min. Pass % 96 min Duration"),
            CourseElement("module2", svg = true, context = "75 % Min. Pass % 71 min Duration"))
        fun page(vararg controls: CourseElement) = snapshot(course, *controls, document = "spa")
        runner.read(page(*dashboard), 0); assertEquals("module1", runner.action?.element); runner.ack()
        val firstChapter = CourseElement("chapter1", label = "1. Safety In Progress 1/1 1 Material s", expanded = true)
        val secondChapter = CourseElement("chapter2", label = "2. Stress Not Started 1 Material s", expanded = false)
        val row1 = CourseElement("row1", label = "Watch video", context = "Part:1 Not Started", href = lesson)
        runner.read(page(firstChapter, secondChapter, row1), 1000); runner.ack()
        runner.read(page(CourseElement("part", tag = "h2", label = "Part:1"), CourseElement("v", tag = "video", ended = true), CourseElement("back", label = "Back to Course")), 2000); runner.ack()
        runner.read(page(firstChapter.copy(expanded = false), secondChapter, row1.copy(label = "Rewatch video", visible = false)), 3000)
        assertEquals("chapter2", runner.action?.element, "Material s must not be mistaken for a finished module")
        runner.ack()
        val row2 = row1.copy(id = "row2", href = "$course/second")
        runner.read(page(firstChapter.copy(expanded = false), secondChapter.copy(expanded = true), row2), 4000)
        assertEquals("row2", runner.action?.element); runner.ack()
        runner.read(page(CourseElement("part", tag = "h2", label = "Part:1"), CourseElement("v2", tag = "video", ended = true), CourseElement("back2", label = "Back to Course")), 5000); runner.ack()
        val home = CourseElement("home", tag = "a", label = "HOME", href = "/dashboard")
        val finished = arrayOf(firstChapter.copy(expanded = false), secondChapter.copy(expanded = false), home)
        runner.read(page(*finished), 6000); assertNull(runner.action)
        runner.read(page(*finished), 8001); assertEquals("home", runner.action?.element); runner.ack()
        runner.read(page(*dashboard), 9000); assertEquals("module2", runner.action?.element); runner.ack()
        val otherFinished = arrayOf(CourseElement("breadcrumb", tag = "a", href = other),
            firstChapter.copy(label = "1. Finance Completed 1 Material s", expanded = false), home)
        runner.read(page(*otherFinished), 10000)
        runner.read(page(*otherFinished), 12001)
        assertEquals("home", runner.action?.element, "The same HOME node must work for each module")
    }

    @Test
    fun `given repeated Part numbers across chapters then actual breadcrumb identity allows next and a final loop returns to the module`() {
        val runner = CourseAutomationSession("session")
        val breadcrumb = CourseElement("course-link", tag = "a", href = course)
        val part = CourseElement("part", tag = "h2", label = "Part:1")
        val done = CourseElement("done", tag = "h3", label = "Video Completed!")
        val next = CourseElement("next", label = "Next")
        val one = CourseElement("lesson-link", tag = "a", href = lesson)
        val two = one.copy(href = "$course/second-chapter")
        fun page(vararg controls: CourseElement) = snapshot(course, breadcrumb, part, *controls, document = "spa")
        runner.read(page(one, done, next), 0); assertEquals("next", runner.action?.element); runner.ack()
        runner.read(page(two, CourseElement("v2", tag = "video", paused = false), next), 1000)
        assertNull(runner.action)
        runner.read(page(two, done, next), 2000); assertEquals("next", runner.action?.element); runner.ack()
        runner.read(page(one, CourseElement("v1", tag = "video", paused = true), next), 3000)
        assertEquals("course-link", runner.action?.element, "Do not replay finished lessons if the final Next wraps")
    }

    @Test
    fun `given a paused video with its own play control then initialize the player before requesting native media playback`() {
        val runner = CourseAutomationSession("session")
        val video = CourseElement("video", tag = "video", paused = true)
        val play = CourseElement("player-play", label = "Play Video")
        runner.read(snapshot(lesson, video, play), 0)
        assertEquals("player-play", runner.action?.element)
        runner.ack()
        runner.read(snapshot(lesson, video.copy(paused = false), play), 1000)
        assertNull(runner.action)
        runner.read(snapshot(lesson, video, play), 2000)
        assertEquals("video", runner.action?.element)
        assertEquals("play", runner.action?.kind)
        runner.ack(false)
        runner.read(snapshot(lesson, video, play), 3000)
        assertNull(runner.action, "Wait before retrying a paused or refused player")
    }

    @Test
    fun `given a video pauses again when retry interval passes then resume without advancing`() {
        val runner = CourseAutomationSession("session")
        val video = CourseElement("video", tag = "video", paused = true)
        val next = CourseElement("next", label = "Next")
        runner.read(snapshot(lesson, video, next), 0)
        assertEquals("play", runner.action?.kind)
        runner.ack()
        runner.read(snapshot(lesson, video.copy(paused = false), next), 1000)
        assertNull(runner.action)
        runner.read(snapshot(lesson, video, next), 4999)
        assertNull(runner.action)
        runner.read(snapshot(lesson, video, next), 5000)
        assertEquals("video", runner.action?.element)
        assertEquals("play", runner.action?.kind)
        runner.ack()
        runner.read(snapshot(lesson, video.copy(ended = true), next), 6000)
        assertEquals("next", runner.action?.element, "Advance only after completion")
    }

    @Test
    fun `given refused playback then retry at intervals and respect configured wait`() {
        val runner = CourseAutomationSession("session", waitAfterClickMillis = 8000)
        val page = snapshot(lesson, CourseElement("video", tag = "video", paused = true))
        runner.read(page, 0)
        runner.ack(false)
        runner.read(page, 4999)
        assertNull(runner.action)
        runner.read(page, 5000)
        assertEquals("play", runner.action?.kind)
        runner.ack()
        runner.read(page, 12999)
        assertNull(runner.action)
        runner.read(page, 13000)
        assertEquals("play", runner.action?.kind)
    }

    @Test
    fun `given decorative or hidden paused videos then never resume them`() {
        val runner = CourseAutomationSession("session")
        val page = snapshot(lesson,
            CourseElement("decorative", tag = "video", paused = true, decorative = true),
            CourseElement("hidden", tag = "video", paused = true, visible = false))
        runner.read(page, 0)
        assertNull(runner.action)
        runner.read(page, 5000)
        assertNull(runner.action)
    }

    @Test
    fun `given dashboard with stale course path and header menus then only an actual course control is clicked`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(course,
            CourseElement("heading", tag = "h2", label = "All Courses"),
            CourseElement("bell", expanded = false),
            CourseElement("profile", label = "JS User", expanded = false),
            CourseElement("card", svg = true, context = "75 % Min. Pass % 96 min Duration")), 0)
        assertEquals("card", runner.action?.element)
    }

    @Test
    fun `given course page with only header dropdowns then no header is treated as a chapter`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(course, CourseElement("bell", expanded = false), CourseElement("profile", label = "JS User", expanded = false)), 0)
        assertNull(runner.action)
    }

    @Test
    fun `given playback speed when saved and exported then it is preserved and legacy routines remain normal speed`() {
        for (speed in listOf(1.0, 1.5, 2.0)) {
            val routine = CourseAutomationRoutine("speed", "Course", playbackSpeed = speed)
            assertEquals(routine, routine.toDraft().toRoutine("speed"))
            assertEquals(listOf(routine), RoutineFileCodec.decode(RoutineFileCodec.encode(listOf(routine)))?.routines)
            assertFalse(routine.toDraft().validate().hasAny)
            assertEquals(speed, CourseAutomationSession("session", playbackSpeed = speed).playbackSpeed)
        }
        val defaultDraft = RoutineDraft(kind = RoutineKind.CourseAutomation, name = "Course")
        assertEquals(1.0, defaultDraft.playbackSpeed)
        assertEquals(1.0, (defaultDraft.toRoutine("default") as CourseAutomationRoutine).playbackSpeed)
        for (speed in listOf(0.0, -1.0, 3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(RoutineDraft(kind = RoutineKind.CourseAutomation, name = "Course", playbackSpeed = speed).validate().hasAny)
            assertEquals(1.0, CourseAutomationSession("session", playbackSpeed = speed).playbackSpeed)
        }
        val legacy = Json.decodeFromString(Routine.serializer(), """{"type":"courseAutomation","id":"old","name":"Course"}""") as CourseAutomationRoutine
        assertEquals(1.0, legacy.playbackSpeed)
    }

    @Test
    fun `given player is removed on completion then Next advances and the following lesson plays before advancing again`() {
        val runner = CourseAutomationSession("session", 5000)
        val title = CourseElement("title", tag = "h1", label = "Part:3")
        val next = CourseElement("next", label = "Next")
        val video = CourseElement("video", tag = "video", paused = false)
        val done = CourseElement("done", tag = "h2", label = "Video Completed!")
        fun page(vararg controls: CourseElement) = snapshot(lesson, *controls, document = "spa")
        runner.read(page(title, video, next), 0)
        assertNull(runner.action)
        runner.read(page(title, done, next, CourseElement("quiz", label = "Start Quiz")), 1000)
        assertEquals("next", runner.action?.element)
        runner.acknowledge(runner.action!!.request, true, 1000)
        runner.read(page(title, done, next), 6000)
        assertNull(runner.action, "Do not click Next again before navigation")
        val nextTitle = title.copy(label = "Part:4")
        runner.read(page(nextTitle, video.copy(id = "video4", paused = true), next), 6001)
        assertEquals("play", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 6001)
        runner.read(page(nextTitle, video.copy(id = "video4"), next), 12000)
        assertNull(runner.action, "Never advance a playing video")
        runner.read(page(nextTitle, done.copy(id = "done4"), next), 13000)
        assertEquals("next", runner.action?.element, "React may reuse the same Next DOM node")
        runner.acknowledge(runner.action!!.request, true, 13000)
        runner.read(page(title.copy(label = "Part:5"), done, CourseElement("course-link", tag = "a", href = course)), 19000)
        assertEquals("course-link", runner.action?.element)
    }

    @Test
    fun `given player disappears without completion evidence then Next remains untouched`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(lesson, CourseElement("next", label = "Next")), 0)
        assertNull(runner.action)
    }

    @Test
    fun `given a chapter video has finished but its quiz keeps the chapter in progress then the next chapter opens`() {
        val runner = CourseAutomationSession("session")
        val chapter = CourseElement("chapter1", label = "1. Mental Health In Progress 0/1 1 Materials", expanded = true)
        runner.read(snapshot(course, chapter, CourseElement("watch", label = "Watch video", context = "Part:1 Not Started")), 0)
        runner.ack()
        runner.read(snapshot(lesson, CourseElement("v", tag = "video", ended = true), CourseElement("back", label = "Back to Course")), 1000)
        runner.ack()
        runner.read(snapshot(course,
            chapter.copy(id = "new-header1", label = "1. Mental Health In Progress 1/1 1 Materials", expanded = false),
            CourseElement("chapter2", label = "2. Stress Not Started 1 Materials", expanded = false), document = "return"), 2000)
        assertEquals("chapter2", runner.action?.element)
    }

    @Test
    fun `given stale dashboard URL reports when real course controls change then every unfinished video is opened after the previous one ends`() {
        val runner = CourseAutomationSession("session")
        val breadcrumb = CourseElement("course", tag = "a", label = "Emotional Wellness", href = course)
        val first = CourseElement("part3", label = "Watch video", context = "1.3 Part:3 VIDEO Not Started Watch video")
        val second = CourseElement("part4", label = "Watch video", context = "1.4 Part:4 VIDEO Not Started Watch video")
        runner.read(snapshot("/dashboard", breadcrumb, first, second), 0)
        assertEquals("part3", runner.action?.element)
        runner.ack()
        val video = CourseElement("video", tag = "video", paused = false)
        val back = CourseElement("back", label = "Back to Course")
        runner.read(snapshot("/dashboard", video, back), 1000)
        assertNull(runner.action, "Never leave while the video is playing")
        runner.read(snapshot("/dashboard", video.copy(paused = true, ended = true), back), 2000)
        assertEquals("back", runner.action?.element)
        runner.ack()
        runner.read(snapshot("/dashboard", breadcrumb, first.copy(label = "Rewatch video", context = "1.3 Part:3 Completed Rewatch video"), second), 3000)
        assertEquals("part4", runner.action?.element)
    }

    @Test
    fun `given the live dashboard with header icons and footer-only course controls then the first actual course is selected`() {
        val first = "COURSE ID - 0p8rzv Emotional Wellness StrongMindsAtSea 75 % Min. Pass % 96 min Duration"
        val second = "COURSE ID - yjh45t Economic Wellness WealthAtSea 75 % Min. Pass % 71 min Duration"
        val runner = CourseAutomationSession("session")
        runner.read(snapshot("/dashboard",
            CourseElement("bell", svg = true, context = "HOME Jagdeep All Courses $first $second"),
            CourseElement("profile", svg = true, context = "HOME Jagdeep All Courses $first COURSE ID - yjh45t Economic Wellness"),
            CourseElement("heading", tag = "h3", label = "Emotional Wellness", context = first),
            CourseElement("play-first", svg = true, context = "75 % Min. Pass % 96 min Duration"),
            CourseElement("play-second", svg = true, context = "75 % Min. Pass % 71 min Duration")), 0)
        assertEquals("play-first", runner.action?.element)
    }

    @Test
    fun `given status-only lesson contexts when the first finishes then the second identical label is still opened`() {
        val runner = CourseAutomationSession("session")
        val rows = arrayOf(CourseElement("watch1", label = "Watch video", context = "Not Started Watch video"),
            CourseElement("watch2", label = "Watch video", context = "Not Started Watch video"))
        runner.read(snapshot(course, *rows), 0)
        assertEquals("watch1", runner.action?.element)
        runner.ack()
        runner.read(snapshot(lesson, CourseElement("video", tag = "video", ended = true), CourseElement("back", label = "Back to Course")), 1000)
        runner.ack()
        runner.read(snapshot(course, *rows, document = "return"), 2000)
        assertEquals("watch2", runner.action?.element)
    }

    @Test
    fun `given a finished row becomes Rewatch video when returning then the next unfinished row keeps its identity`() {
        val runner = CourseAutomationSession("session")
        val first = CourseElement("watch1", label = "Watch video", context = "Not Started Watch video")
        val second = CourseElement("watch2", label = "Watch video", context = "Not Started Watch video")
        runner.read(snapshot(course, first, second), 0)
        runner.ack()
        runner.read(snapshot(lesson, CourseElement("video", tag = "video", ended = true), CourseElement("back", label = "Back to Course")), 1000)
        runner.ack()
        runner.read(snapshot(course, first.copy(label = "Rewatch video", context = "Completed Rewatch video"), second, document = "return"), 2000)
        assertEquals("watch2", runner.action?.element)
    }

    @Test
    fun `given a five second wait when a click opens another page then no action is sent before the wait expires`() {
        val runner = CourseAutomationSession("session", waitAfterClickMillis = 5000)
        runner.read(snapshot("/dashboard", CourseElement("course", href = course, context = "Wellness Min. Pass 75% Duration 71min")), 0)
        runner.acknowledge(runner.action!!.request, true, now = 1000)
        val page = snapshot(course, CourseElement("watch", label = "Watch video", context = "Part 1 Not started"))
        runner.read(page, 1001)
        assertNull(runner.action)
        assertTrue(runner.detail.startsWith("Waiting 5 seconds"))
        runner.read(page, 5999)
        assertNull(runner.action)
        runner.read(page, 6000)
        assertEquals("watch", runner.action?.element)
    }

    @Test
    fun `given a custom wait when a chapter opens in the same document then the next click waits too`() {
        val runner = CourseAutomationSession("session", waitAfterClickMillis = 12000)
        runner.read(snapshot(course, CourseElement("chapter", label = "Chapter 1", expanded = false)), 0)
        runner.acknowledge(runner.action!!.request, true, now = 100)
        val page = snapshot(course, CourseElement("watch", label = "Watch video", context = "Part 1 Not started"))
        runner.read(page, 12099)
        assertNull(runner.action)
        runner.read(page, 12100)
        assertEquals("watch", runner.action?.element)
    }

    @Test
    fun `given saved routine timing when edited or imported then it is preserved and invalid waits are rejected`() {
        val routine = CourseAutomationRoutine("custom", "My course", waitAfterClickMillis = 12000)
        assertEquals("5", RoutineDraft(kind = RoutineKind.CourseAutomation).waitAfterClickSeconds)
        assertEquals("12", routine.toDraft().waitAfterClickSeconds)
        assertEquals(routine, routine.toDraft().toRoutine("custom"))
        assertEquals(listOf(routine), RoutineFileCodec.decode(RoutineFileCodec.encode(listOf(routine)))?.routines)
        listOf("", "-1", "1.5", "86401", "invalid").forEach {
            assertTrue(routine.toDraft().copy(waitAfterClickSeconds = it).validate().hasAny)
        }
        val legacy = Json.decodeFromString(Routine.serializer(), """{"type":"courseAutomation","id":"old","name":"Course"}""") as CourseAutomationRoutine
        assertEquals(5000, legacy.waitAfterClickMillis)
    }

    @Test
    fun `given dashboard course and lesson when video ends then the app opens the next part and returns once`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot("/dashboard", CourseElement("course", href = course, context = "Wellness Min. Pass 75% Duration 71min")), 0)
        assertEquals("course", runner.action?.element)
        runner.ack()
        val row = CourseElement("watch", label = "Watch video", context = "Part 1 Video Not started Watch video")
        runner.read(snapshot(course, row, CourseElement("quiz", label = "Assessment")), 1000)
        assertEquals("watch", runner.action?.element)
        runner.ack()
        val video = CourseElement("v", tag = "video")
        val back = CourseElement("back", label = "Back to Course")
        runner.read(snapshot(lesson, video, back), 2000)
        assertEquals("play", runner.action?.kind)
        runner.ack()
        runner.read(snapshot(lesson, video.copy(paused = false), back), 3000)
        assertNull(runner.action)
        runner.read(snapshot(lesson, video.copy(ended = true), back), 4000)
        assertEquals("back", runner.action?.element)
        runner.ack()
        runner.read(snapshot(lesson, video.copy(ended = true), back), 5000)
        assertNull(runner.action)
        runner.read(snapshot(course, row, document = "returned"), 6000)
        runner.read(snapshot(course, row, document = "returned"), 8000)
        assertTrue(runner.completed, "Already-watched lesson is not opened again when the site status lags")
        assertEquals(3, runner.clicks)
    }

    @Test
    fun `given hidden disabled completed and quiz controls when choosing then only an unfinished video is clicked`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(course,
            CourseElement("disabled", label = "Watch video", context = "Not started", enabled = false),
            CourseElement("hidden", label = "Watch video", context = "Not started", visible = false),
            CourseElement("done", label = "Watch video", context = "VIDEOCompletedWatch video"),
            CourseElement("quiz", label = "Start quiz", context = "Not started"),
            CourseElement("next", label = "Watch video", context = "Part 2 In progress")), 0)
        assertEquals("next", runner.action?.element)
    }

    @Test
    fun `given denied playback when the user plays it then automation waits for the real end`() {
        val runner = CourseAutomationSession("session")
        val video = CourseElement("v", tag = "video")
        val back = CourseElement("back", label = "Back to Course")
        runner.read(snapshot(lesson, video, back), 0)
        runner.ack(false)
        runner.read(snapshot(lesson, video, back), 1000)
        assertNull(runner.action)
        assertTrue(runner.detail.contains("Press play"))
        runner.read(snapshot(lesson, video.copy(paused = false), back), 2000)
        assertNull(runner.action)
        runner.read(snapshot(lesson, video.copy(ended = true), back), 3000)
        assertEquals("back", runner.action?.element)
    }

    @Test
    fun `given an old session or unrelated path or truncated snapshot then no action is issued`() {
        val target = CourseElement("watch", label = "Watch video", context = "Not started")
        listOf(snapshot(course, target).copy(session = "old"), snapshot("/account", target), snapshot(course, target).copy(truncated = true)).forEach {
            val runner = CourseAutomationSession("session")
            runner.read(it, 0)
            assertNull(runner.action)
        }
    }

    @Test
    fun `given missing acknowledgement when timed out then it never retries the same click`() {
        val runner = CourseAutomationSession("session")
        val page = snapshot(course, CourseElement("watch", label = "Watch video", context = "Not started"))
        runner.read(page, 0)
        runner.read(page, 16_000)
        runner.read(page, 20_000)
        assertNull(runner.action)
        assertEquals(0, runner.clicks)
        assertTrue(runner.detail.contains("confirm"))
    }

    @Test
    fun `given a disabled unfinished lesson when other lessons are done then the course does not finish early`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(course,
            CourseElement("done", label = "Watch video", context = "Part 1 Completed"),
            CourseElement("locked", label = "Watch video", context = "Part 2 Not started", enabled = false)), 0)
        assertFalse(runner.completed)
        assertNull(runner.action)
        assertTrue(runner.detail.contains("available"))
    }

    @Test
    fun `given a second iframe video still playing when the first ends then Back to Course is not clicked`() {
        val runner = CourseAutomationSession("session")
        runner.read(snapshot(lesson, CourseElement("v", tag = "video", paused = false), document = "frame").copy(top = false), 0)
        runner.read(snapshot(lesson, CourseElement("v", tag = "video", ended = true), CourseElement("back", label = "Back to Course")), 1000)
        assertNull(runner.action)
    }

    @Test
    fun `given chunked inventory when complete then it is assembled and partial or reordered chunks never act`() {
        val assembler = CourseSnapshotAssembler()
        val page = snapshot(course, CourseElement("w", label = "Watch video"))
        val encoded = Json.encodeToString(CourseSnapshot.serializer(), page)
        val split = encoded.length / 2
        val first = CourseWireEvent("courseChunk", "session", "a", 0, 2, encoded.take(split))
        val second = first.copy(index = 1, chunk = encoded.drop(split))
        assertNull(assembler.accept(second))
        assertNull(assembler.accept(first))
        assertEquals(page, assembler.accept(second))
        assertNull(assembler.accept(first.copy(total = 2001)))
        assertNull(assembler.accept(first.copy(chunk = "x".repeat(1001))))
    }

    @Test
    fun `given a new automation routine when saved and exported then its independent type survives`() {
        val routine = CourseAutomationRoutine("new", "My course")
        assertFalse(routine.needsInputAccess())
        assertEquals(routine, routine.toDraft().toRoutine("new"))
        assertEquals(listOf(routine), RoutineFileCodec.decode(RoutineFileCodec.encode(listOf(routine)))?.routines)
    }
}
