package com.jagdeep.home.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseAssessmentTest {
    @Test
    fun `practice quiz answers and submits each question before advancing`() {
        val runner = CourseAutomationSession("s", 0, answerQuizzes = true, assessmentMode = AssessmentMode.Review)
        runner.configureAnswers(bank)
        val controls = arrayOf(CourseElement("a", tag = "button", label = "1. First"), CourseElement("b", tag = "button", label = "2. Second"), CourseElement("submit", tag = "button", label = "Submit"))
        runner.read(page(*controls, assessment = CourseAssessment("Wellness", listOf(q), submit = "submit", quiz = true, practice = true, totalQuestions = 2)), 0)
        assertEquals("assessmentAnswer", runner.action?.kind)
        assertEquals("b", runner.action?.element)
        runner.acknowledge(runner.action!!.request, true)
        runner.read(page(*controls, assessment = CourseAssessment("Wellness", listOf(q.copy(options = q.options.map { it.copy(selected = it.id == "b") })), submit = "submit", quiz = true, practice = true, totalQuestions = 2)), 1)
        assertEquals("assessmentSubmit", runner.action?.kind)
        assertNull(runner.approval)
    }

    @Test
    fun `practice quiz may try an unknown answer and submit without approval`() {
        val runner = CourseAutomationSession("s", 0, answerQuizzes = true, assessmentMode = AssessmentMode.Manual)
        val unknown = q.copy(text = "A new practice question?")
        val controls = arrayOf(CourseElement("a", tag = "button", label = "1. First"), CourseElement("b", tag = "button", label = "2. Second"), CourseElement("submit", tag = "button", label = "Submit"))
        runner.read(page(*controls, assessment = CourseAssessment("Wellness", listOf(unknown), submit = "submit", quiz = true, practice = true)), 0)
        assertEquals("assessmentAnswer", runner.action?.kind)
        assertEquals("a", runner.action?.element)
        runner.acknowledge(runner.action!!.request, true)
        runner.read(page(*controls, assessment = CourseAssessment("Wellness", listOf(unknown.copy(options = unknown.options.map { it.copy(selected = it.id == "a") })), submit = "submit", quiz = true, practice = true)), 1)
        assertEquals("assessmentSubmit", runner.action?.kind)
        assertNull(runner.approval)
    }

    @Test
    fun `practice quiz finishes last question before recording completion`() {
        val runner = CourseAutomationSession("s", 0, answerQuizzes = true, assessmentMode = AssessmentMode.Review)
        val finish = CourseElement("finish", tag = "button", label = "Finish")
        val answered = q.copy(options = q.options.map { it.copy(enabled = false) })
        runner.read(page(finish, assessment = CourseAssessment("Wellness", listOf(answered), next = "finish", quiz = true,
            practice = true, practiceFeedback = true, totalQuestions = 2)), 0)
        assertEquals("assessmentNext", runner.action?.kind)
        assertTrue(runner.pendingCompletions.isEmpty())

        runner.acknowledge(runner.action!!.request, true)
        runner.read(page(CourseElement("continue", tag = "button", label = "Go to Next Material"),
            assessment = CourseAssessment("Wellness", result = "completed", quiz = true, practice = true)), 1)
        assertEquals("assessmentReturn", runner.action?.kind)
        assertEquals("continue", runner.action?.element)
        assertTrue(runner.pendingCompletions.values.any { it.kind == "quiz" })
        assertNull(runner.approval)
    }

    @Test
    fun `quiz start screen uses its own start control`() {
        val runner = CourseAutomationSession("s", 0, answerAssessments = false, answerQuizzes = true, assessmentMode = AssessmentMode.Automatic)
        runner.configureAnswers(bank)
        runner.read(page(CourseElement("start", label = "Start Quiz"), assessment = CourseAssessment("Wellness", start = "start", quiz = true)), 0)
        assertEquals("assessmentStart", runner.action?.kind)
        assertEquals("start", runner.action?.element)
    }

    @Test
    fun `quiz start is allowed before its module has learned answers`() {
        val runner = CourseAutomationSession("s", 0, answerQuizzes = true, assessmentMode = AssessmentMode.Automatic)
        runner.read(page(CourseElement("start", tag = "button", label = "Start Quiz"),
            assessment = CourseAssessment("New module", start = "start", quiz = true)), 0)
        assertEquals("assessmentStart", runner.action?.kind)
        assertEquals("start", runner.action?.element)
        assertNull(runner.approval)
        assertFalse(runner.detail.contains("No local answer bank"))
    }

    @Test
    fun `quiz answering uses the saved bank only when quiz setting is enabled`() {
        val disabled = CourseAutomationSession("s", answerAssessments = false, answerQuizzes = false, assessmentMode = AssessmentMode.Automatic)
        disabled.configureAnswers(bank)
        disabled.read(page(assessment = CourseAssessment("Wellness", listOf(q), quiz = true)), 0)
        assertNull(disabled.action)
        assertTrue(disabled.detail.contains("Quiz handling is disabled"))

        val enabled = CourseAutomationSession("s", answerAssessments = false, answerQuizzes = true, assessmentMode = AssessmentMode.Automatic)
        enabled.configureAnswers(bank)
        enabled.read(page(assessment = CourseAssessment("Wellness", listOf(q), quiz = true)), 0)
        assertEquals("assessmentAnswer", enabled.action?.kind)
        assertEquals("b", enabled.action?.element)
    }

    @Test
    fun `automatic mode resumes existing questions without starting another attempt`() {
        val runner = CourseAutomationSession("s", answerAssessments = true, assessmentMode = AssessmentMode.Automatic)
        runner.configureAnswers(bank)
        runner.read(page(assessment = CourseAssessment("Wellness", listOf(q))).copy(path = "$course/test/existing"), 0)
        assertEquals("assessmentAnswer", runner.action?.kind)
        assertEquals("b", runner.action?.element)
        assertNull(runner.approval)
        assertTrue(runner.pendingCompletions.isEmpty(), "Resuming must not invent video completion")
    }

    @Test
    fun `blank live assessment module reuses the course title for automatic answers`() {
        val runner = CourseAutomationSession("s", answerAssessments = true, assessmentMode = AssessmentMode.Automatic)
        runner.configureAnswers(bank)
        val snapshot = CourseSnapshot("s", "doc", "$course/test/active", elements = listOf(
            CourseElement("module", tag = "a", label = "Wellness", href = course), submit),
            assessment = CourseAssessment("", listOf(q), submit = "submit", totalQuestions = 1))

        runner.read(snapshot, 0)

        assertEquals("Wellness", runner.currentModule)
        assertEquals("assessmentAnswer", runner.action?.kind)
        assertEquals("b", runner.action?.element)
    }

    @Test
    fun `review resumes answers automatically while manual never operates assessment controls`() {
        for (mode in listOf(AssessmentMode.Review, AssessmentMode.Manual)) {
            val runner = CourseAutomationSession("s", answerAssessments = true, assessmentMode = mode)
            runner.configureAnswers(bank)
            runner.read(page(assessment = CourseAssessment("Wellness", listOf(q))), 0)
            assertNull(runner.approval)
            if (mode == AssessmentMode.Review) assertEquals("assessmentAnswer", runner.action?.kind)
            else { assertNull(runner.action); assertNull(runner.attention); assertTrue(runner.detail.contains("Manual mode")) }
        }
    }

    @Test
    fun `resuming still pauses for unknown answers and disabled handling`() {
        for (enabled in listOf(true, false)) {
            val runner = CourseAutomationSession("s", answerAssessments = enabled, assessmentMode = AssessmentMode.Automatic)
            runner.configureAnswers(bank)
            runner.read(page(assessment = CourseAssessment("Wellness", listOf(q.copy(text = "Unknown?")))) , 0)
            assertNull(runner.action)
            if (enabled) assertNotNull(runner.attention) else assertTrue(runner.detail.contains("disabled"))
        }
    }

    @Test
    fun `videos first pauses even in automatic mode then revisits only unfinished assessments after approval`() {
        val runner = CourseAutomationSession("s",0,answerAssessments=true,assessmentMode=AssessmentMode.Automatic,learningOrder=LearningOrder.VideosFirst)
        runner.configureAnswers(bank)
        runner.configureProgress(CourseProgressSummary("user","User",1,listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user","passed","Passed module","assessment","Course Assessment","passed",90.0,observedAt=1),
            com.jagdeep.persistence.room.learning.LearningRecord("user","passed-videos","Passed module","module-videos","All module videos","completed",details="/dashboard/courses/passed",observedAt=1))))
        val done = page(home,CourseElement("module",tag="a",href=course,label="Wellness"),CourseElement("done",label="Rewatch video",context="Part:1 Completed"),CourseElement("assessment",label="Assessment"))
        runner.read(done,0); runner.read(done,2000)
        assertEquals("home",runner.action?.element)
        runner.acknowledge(runner.action!!.request,true)
        val dashboard = CourseSnapshot("s","dash","/dashboard",elements=listOf(
            CourseElement("passed",tag="a",href="/dashboard/courses/passed",courseTitle="Passed module",context="Passed module Completed Min. Pass 75% Duration 20min"),
            CourseElement("pending",tag="a",href=course,courseTitle="Wellness",context="Wellness In Progress Min. Pass 75% Duration 20min")))
        runner.read(dashboard,3000); runner.read(dashboard,5000)
        assertEquals(CourseApprovalKind.StartAssessmentPass,runner.approval?.kind)
        assertNull(runner.action)
        assertFalse(runner.completed)
        val token = runner.approval!!.token
        runner.approveAssessment("stale")
        runner.read(dashboard,6000)
        assertEquals(token,runner.approval?.token)
        runner.approveAssessment(token)
        runner.read(dashboard,7000)
        assertEquals("pending",runner.action?.element,"A saved pass must not be revisited for assessment")
        runner.acknowledge(runner.action!!.request,true)
        runner.read(done,8000); runner.read(done,10000)
        assertEquals("assessmentOpen",runner.action?.kind)
        assertEquals("assessment",runner.action?.element)
    }

    @Test
    fun `videos first never opens an assessment or asks for answers after module videos are done`() {
        val runner = CourseAutomationSession("s",0,answerAssessments=true,learningOrder=LearningOrder.VideosFirst)
        val done = page(home,CourseElement("done",label="Rewatch video",context="Part:1 Completed"),CourseElement("assessment",label="Assessment"))
        runner.read(done,0); runner.read(done,2000)
        assertEquals("home",runner.action?.element)
        assertNull(runner.approval)
        assertNull(runner.attention)
        assertEquals(0,runner.progressCheckpoint)
        runner.acknowledge(runner.action!!.request,true)
        runner.read(page(home,assessment=CourseAssessment("Wellness",start="start")),3000)
        assertNull(runner.action)
        assertNull(runner.approval)
    }

    @Test
    fun `videos first does not skip the last course card when its context says completed`() {
        val runner = CourseAutomationSession("s", 0, answerAssessments = true, learningOrder = LearningOrder.VideosFirst)
        val records = (1..9).map { index ->
            com.jagdeep.persistence.room.learning.LearningRecord(
                "user", "videos-$index", "Module $index", "module-videos", "All module videos", "completed",
                details = "/dashboard/courses/module-$index", observedAt = index.toLong()
            )
        }
        runner.configureProgress(CourseProgressSummary("user", "User", 1, records))
        val cards = (1..10).map { index ->
            CourseElement(
                "module-$index", tag = "a", label = "Module $index", href = "/dashboard/courses/module-$index",
                courseTitle = "Module $index",
                context = "Module $index Completed Min. Pass 75% Duration 20min"
            )
        }
        runner.read(CourseSnapshot("s", "dashboard", "/dashboard", elements = cards), 0)
        assertEquals("module-10", runner.action?.element)
        assertEquals("Module 10", runner.currentModule)
    }

    @Test
    fun `dashboard module title identifies an existing pass without relying on course breadcrumbs`() {
        val runner = CourseAutomationSession("s",0,answerAssessments=true)
        runner.configureAnswers(bank)
        runner.configureProgress(CourseProgressSummary("user","User",1,listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user","old","Wellness","assessment","Course Assessment","passed",90.0,observedAt=1))))
        runner.read(CourseSnapshot("s","dash","/dashboard",elements=listOf(
            CourseElement("card",tag="a",href=course,context="Wellness Min. Pass 75% Duration 20min",courseTitle="Wellness"))),0)
        assertEquals("Wellness",runner.currentModule)
        runner.acknowledge(runner.action!!.request,true)
        val done = page(home,CourseElement("done",label="Rewatch video",context="Part:1 Completed"),CourseElement("assessment",label="Assessment"))
        runner.read(done,1000); runner.read(done,3000)
        assertNull(runner.approval)
        assertEquals("home",runner.action?.element)
        assertEquals(0,runner.progressCheckpoint)
    }

    @Test
    fun `given history confirms pass and course confirms videos done then skip a new assessment`() {
        val runner=CourseAutomationSession("s",0,answerAssessments=true)
        runner.configureAnswers(bank)
        runner.configureProgress(CourseProgressSummary("user","User",1,listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user","old","Wellness","assessment","Course Assessment","passed",90.0,observedAt=1),
            com.jagdeep.persistence.room.learning.LearningRecord("user","new","Wellness","assessment","Course Assessment","unsubmitted",observedAt=2))))
        val snapshot=page(home,CourseElement("module",tag="a",href=course,label="Wellness"),
            CourseElement("done",label="Rewatch video",context="Part:1 Completed"),CourseElement("assessment",label="Assessment"))
        runner.read(snapshot,0); runner.read(snapshot,2000)
        assertNull(runner.approval)
        assertEquals("home",runner.action?.element)
        assertEquals(0,runner.progressCheckpoint,"Skipping an existing pass must not trigger another progress scan")
    }

    @Test
    fun `given user manually submits a started assessment and site reports passed then refresh progress`() {
        val runner = ready()
        runner.read(page(home, assessment=CourseAssessment("Wellness",result="passed")),3000)
        assertEquals("assessmentReturn",runner.action?.kind)
        assertEquals(1,runner.progressCheckpoint)
    }

    @Test
    fun `given saved failed attempt then automatic mode asks before starting another`() {
        for (status in listOf("failed")) {
            val runner = CourseAutomationSession("s",0,answerAssessments=true,assessmentMode=AssessmentMode.Automatic)
            runner.configureAnswers(bank)
            runner.configureProgress(CourseProgressSummary("user","User",1,listOf(
                com.jagdeep.persistence.room.learning.LearningRecord("user","attempt","Wellness","assessment","Course Assessment",status,observedAt=1))))
            val snapshot = page(CourseElement("module",tag="a",label="Wellness",href=course),
                CourseElement("done",label="Rewatch video",context="Part:1 Completed"),CourseElement("assessment",label="Assessment"))
            runner.read(snapshot,0); runner.read(snapshot,2000)
            assertNull(runner.action)
            assertEquals(CourseApprovalKind.StartAssessment,runner.approval?.kind)
        }
    }

    @Test
    fun `given automatic mode and unsubmitted progress then open and start an unfinished assessment`() {
        val runner = CourseAutomationSession("s", 0, answerAssessments = true, assessmentMode = AssessmentMode.Automatic)
        runner.configureAnswers(bank)
        runner.configureProgress(CourseProgressSummary("user", "User", 1, listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user", "attempt", "Wellness", "assessment", "Course Assessment", "unsubmitted", observedAt = 1))))
        val done = page(CourseElement("module", tag = "a", label = "Wellness", href = course),
            CourseElement("done", label = "Rewatch video", context = "Part:1 Completed"), CourseElement("assessment", label = "Assessment"))
        runner.read(done, 0); runner.read(done, 2000)
        assertNull(runner.approval)
        assertEquals("assessmentOpen", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true)
        runner.read(page(CourseElement("start", label = "Start Assessment"), assessment = CourseAssessment("Wellness", start = "start")), 3000)
        assertNull(runner.approval)
        assertEquals("assessmentStart", runner.action?.kind)
    }

    @Test
    fun `manual mode without a bank leaves starts and selected-question navigation to the user`() {
        val runner = CourseAutomationSession("s", 0, answerAssessments = true, assessmentMode = AssessmentMode.Manual)
        runner.read(page(CourseElement("start", label = "Start Assessment"), assessment = CourseAssessment("Wellness", start = "start")), 0)
        assertNull(runner.approval)
        assertNull(runner.action)
        val selected = q.copy(options = q.options.map { it.copy(selected = it.id == "b") })
        runner.read(page(submit, CourseElement("next", label="Next"), assessment = CourseAssessment("Wellness", listOf(selected), next="next", submit="submit", totalQuestions=1)), 1000)
        assertNull(runner.action)
        assertNull(runner.approval)
        assertNotNull(runner.manualAnswer)
        assertTrue(runner.acceptManualAnswer(runner.manualAnswer!!.token))
        runner.read(page(submit, assessment = CourseAssessment("Wellness", listOf(selected), submit="submit", totalQuestions=1)), 2000)
        assertNull(runner.action)
        assertNull(runner.approval)
    }

    @Test
    fun `given automatic mode then known answers submit without another approval while manual mode waits`() {
        fun started(mode: AssessmentMode): CourseAutomationSession {
            val runner = CourseAutomationSession("s", 0, answerAssessments = true, assessmentMode = mode)
            runner.configureAnswers(bank)
            val complete = CourseElement("rewatch", label = "Rewatch video", context = "Part:1 Completed")
            val snapshot = page(complete, CourseElement("assessment", label = "Assessment"))
            runner.read(snapshot, 0)
            runner.read(snapshot, 2000)
            runner.action?.let { runner.acknowledge(it.request, true, 2000) }
            return runner
        }
        val automatic = started(AssessmentMode.Automatic)
        val selected = q.copy(options = q.options.map { it.copy(selected = it.id == "b") })
        automatic.read(page(submit, assessment = CourseAssessment("Wellness", listOf(selected), submit = "submit", totalQuestions = 1)), 3000)
        assertNull(automatic.approval)
        assertEquals("assessmentSubmit", automatic.action?.kind)
        val manual = started(AssessmentMode.Manual)
        manual.read(page(submit, assessment = CourseAssessment("Wellness", listOf(q), submit = "submit", totalQuestions = 1)), 3000)
        assertNull(manual.action)
        assertNull(manual.attention)
        val routine = CourseAutomationRoutine(id = "r", name = "Course", answerAssessments = true, assessmentMode = AssessmentMode.Automatic)
        assertEquals(AssessmentMode.Automatic, (routine.toDraft().toRoutine("r") as CourseAutomationRoutine).assessmentMode)
    }

    @Test
    fun `given a manually selected unknown answer then confirmation is required before using it for this attempt`() {
        val runner = ready()
        val unknown = q.copy(text = "New question?", options = q.options.map { it.copy(selected = it.id == "b") })
        val snapshot = page(submit, assessment = CourseAssessment("Wellness", listOf(unknown), submit = "submit", totalQuestions = 1))
        runner.read(snapshot, 3000)
        assertNull(runner.action)
        val request = runner.manualAnswer!!
        assertEquals("2. Second", request.answer)
        assertFalse(runner.acceptManualAnswer("old-token"))
        assertTrue(runner.acceptManualAnswer(request.token))
        runner.read(snapshot, 4000)
        assertNull(runner.manualAnswer)
        assertNull(runner.attention)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
        assertNull(bank.answer("Wellness", unknown), "Continue without saving must not mutate the original bank")
    }

    @Test
    fun `given a failed attempt then only an explicit retry starts one attempt and requires fresh submission approval`() {
        val runner = ready()
        val first = page(submit, assessment = CourseAssessment("Wellness", listOf(q), submit = "submit", totalQuestions = 1))
        runner.read(first, 3000)
        assertEquals("assessmentAnswer", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 3000)
        val selected = first.copy(assessment = first.assessment!!.copy(questions = listOf(q.copy(options = q.options.map { it.copy(selected = it.id == "b") }))))
        runner.read(selected, 4000)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(selected, 4000)
        runner.acknowledge(runner.action!!.request, true, 4000)
        val retry = CourseElement("retry", label = "Re-attempt")
        val failed = page(retry, assessment = CourseAssessment("Wellness", result = "failed", retry = "retry")).copy(path = "$course/assessment/result/first")
        runner.read(failed, 5000)
        assertNull(runner.action)
        assertEquals("2. Second", runner.failure!!.questions.single().selectedAnswer)
        assertEquals("2. Second", runner.failure!!.questions.single().bankAnswer)
        assertFalse(runner.retryAssessment("old-failure"))
        assertTrue(runner.retryAssessment(runner.failure!!.token))
        runner.read(failed, 6000)
        assertEquals("assessmentRetry", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 6000)
        runner.read(first.copy(path = "$course/test/test1"), 7000)
        assertEquals("assessmentAnswer", runner.action?.kind, "Old controls and confirmed answers must not skip the new attempt")
        runner.acknowledge(runner.action!!.request, true, 7000)
        runner.read(selected.copy(path = "$course/test/test1"), 8000)
        assertNull(runner.action)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
    }

    private val course = "/dashboard/courses/first"
    private val fixture = """{"Wellness":[{"question":"Which option is correct?","options":["1. First","2. Second"],"answer":"2. Second"}]}"""
    private val bank = CourseAnswerBank.decode(fixture)!!
    private val q = CourseQuestion("q", "Which option is correct?", listOf(CourseOption("a", "1. First"), CourseOption("b", "2. Second")))
    private val home = CourseElement("home", tag = "a", label = "HOME", href = "/dashboard")
    private val submit = CourseElement("submit", label = "Submit Assessment")
    private fun page(vararg controls: CourseElement, assessment: CourseAssessment? = null, top: Boolean = true) =
        CourseSnapshot("s", "doc", course, top = top, elements = controls.toList(), assessment = assessment)
    private fun ready(wait: Long = 0): CourseAutomationSession {
        val runner = CourseAutomationSession("s", wait, answerAssessments = true)
        runner.configureAnswers(bank)
        val complete = CourseElement("rewatch", label = "Rewatch video", context = "Part:1 Completed")
        runner.read(page(complete, CourseElement("assessment", label = "Assessment")), 0)
        runner.read(page(complete, CourseElement("assessment", label = "Assessment")), 2000)
        assertEquals(CourseApprovalKind.StartAssessment, runner.approval?.kind)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(page(complete, CourseElement("assessment", label = "Assessment")), 2000)
        assertEquals("assessmentOpen", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 2000)
        return runner
    }

    @Test
    fun `given bank answers then exact text and complete option sets are required`() {
        assertEquals("b", bank.answer("wellness", q.copy(options = q.options.reversed()))?.id)
        assertEquals("b", bank.answer("Wellness", q.copy(options = q.options.map { it.copy(text = it.text.substringAfter(". ")) }))?.id)
        assertNull(bank.answer("Other", q))
        assertNull(bank.answer("Wellness", q.copy(text = "Which option is NOT correct?")))
        assertNull(bank.answer("Wellness", q.copy(options = q.options + CourseOption("c", "Third"))))
        assertNull(bank.answer("Wellness", q.copy(options = q.options.map { it.copy(text = "First") })))
        assertNull(CourseAnswerBank.decode(fixture.replace("\"answer\":\"2. Second\"", "\"answer\":\"Unknown\"")))
        assertNull(CourseAnswerBank.decode("{}"))
        assertNull(CourseAnswerBank.decode("not json"))
        val entry = """{"question":"Question?","options":["A","B"],"answer":"B"}"""
        assertNotNull(CourseAnswerBank.decode("""{"Module":[$entry,$entry]}"""))
        assertNull(CourseAnswerBank.decode("""{"Module":[$entry,${entry.replace("\"answer\":\"B\"", "\"answer\":\"A\"")}]}"""))
    }

    @Test
    fun `given approved submission then confirm its modal once and recognize the live result route`() {
        val runner = ready()
        val selected = CourseAssessment("Wellness", listOf(q.copy(options = q.options.map { it.copy(selected = it.id == "b") })), submit = "submit")
        val confirm = CourseElement("confirm", label = "End Test")
        runner.read(page(submit, confirm, assessment = selected.copy(confirmSubmit = "confirm")), 3000)
        assertNull(runner.action, "A confirmation modal cannot bypass submission approval")
        runner.approveAssessment(runner.approval!!.token)
        runner.read(page(submit, assessment = selected), 4000)
        assertEquals("assessmentSubmit", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 4000)
        runner.read(page(confirm, assessment = selected.copy(confirmSubmit = "confirm")), 5000)
        assertEquals("assessmentConfirmSubmit", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 5000)
        runner.read(page(confirm, assessment = selected.copy(confirmSubmit = "confirm")), 6000)
        assertNull(runner.action)
        val back = CourseElement("back", label = "Back home")
        runner.read(page(back, assessment = selected.copy(result = "passed")).copy(path = "$course/assessment/result/result1"), 7000)
        assertEquals("assessmentReturn", runner.action?.kind)
        assertEquals("back", runner.action?.element)
    }

    @Test
    fun `given a combined bank then unanswered questions remain unknown and option variants match separately`() {
        val merged = CourseAnswerBank.decode("""{"Module":[
            {"question":"Known?","options":["A","B"],"answer":"B"},
            {"question":"Known?","options":["A","B"],"answer":null},
            {"question":"Known?","options":["C","D"],"answer":"C"},
            {"question":"Unknown?","options":["A","B"],"answer":null},
            {"question":"Missing answer?","options":["A","B"]},
            {"question":"Ambiguous options?","options":["A","A","B"],"answer":"B"}
        ]}""")!!
        fun question(text: String, vararg options: String) = CourseQuestion("q", text, options.map { CourseOption(it, it) })
        assertEquals(5, merged.module("Module")!!.size)
        assertEquals("B", merged.answer("Module", question("Known?", "B", "A"))?.text)
        assertEquals("C", merged.answer("Module", question("Known?", "D", "C"))?.text)
        assertNull(merged.answer("Module", question("Known?", "A", "C")))
        assertNull(merged.answer("Module", question("Unknown?", "A", "B")))
        assertNull(merged.answer("Module", question("Missing answer?", "A", "B")))
        assertNull(merged.answer("Module", question("Ambiguous options?", "A", "A", "B")))
    }

    @Test
    fun `given a failed result at app start then show failure without requiring lesson history or retrying`() {
        val runner = CourseAutomationSession("s", answerAssessments = true)
        runner.read(page(CourseElement("retake", label = "Re-attempt"), assessment = CourseAssessment("Wellness", result = "failed")).copy(path = "$course/assessment/result/result1"), 0)
        assertNull(runner.action)
        assertNull(runner.approval)
        assertTrue(runner.detail.contains("did not pass"))
        assertTrue(runner.detail.contains("not be retried"))
    }

    @Test
    fun `review asks before starting an open assessment and then answers`() {
        val runner = CourseAutomationSession("s", answerAssessments = true).apply { configureAnswers(bank) }
        runner.configureProgress(CourseProgressSummary("user", "User", 1, listOf(
            com.jagdeep.persistence.room.learning.LearningRecord("user", "videos", "Wellness", "module-videos", "All module videos", "completed", details = course, observedAt = 1)
        )))
        val modal = page(CourseElement("start", label = "Start Assessment"), assessment = CourseAssessment("Wellness", start = "start"))
        runner.read(modal, 0)
        assertEquals(CourseApprovalKind.StartAssessment, runner.approval?.kind)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(modal, 0)
        assertEquals("assessmentStart", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 0)
        runner.read(page(assessment = CourseAssessment("Wellness", listOf(q))).copy(path = "$course/test/test1"), 2000)
        assertEquals("assessmentAnswer", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 2000)
        runner.read(modal, 3000)
        assertNull(runner.action)
        assertTrue(runner.detail.contains("No additional attempt"))
    }

    @Test
    fun `given a resume video row then it must be opened before assessment approval`() {
        val runner = CourseAutomationSession("s", answerAssessments = true).apply { configureAnswers(bank) }
        val chapter = CourseElement("chapter", label = "2.2 Saving In Progress 3/4 4 Materials", expanded = true)
        runner.read(page(chapter, CourseElement("resume", label = "Resume video", context = "2.1 Part:1 VIDEO In Progress 69% Resume video"), CourseElement("done", label = "Rewatch video", context = "Part:2 Completed"), CourseElement("assessment", label = "Assessment")), 0)
        assertEquals("resume", runner.action?.element)
        assertNull(runner.approval)
        runner.acknowledge(runner.action!!.request, true, 0)
        runner.read(page(CourseElement("ended", tag = "video", ended = true), CourseElement("next", label = "Next"), CourseElement("course", tag = "a", label = "Wellness", href = course)).copy(path = "$course/lesson1"), 1000)
        assertEquals("course", runner.action?.element, "Return to module status instead of replaying an already completed next lesson")
    }

    @Test
    fun `given unfinished videos then assessment is never opened`() {
        val runner = CourseAutomationSession("s", answerAssessments = true).apply { configureAnswers(bank) }
        runner.read(page(CourseElement("watch", label = "Watch video", context = "Part:1 Not Started"), CourseElement("assessment", label = "Assessment")), 0)
        assertEquals("watch", runner.action?.element)
    }

    @Test
    fun `review blocks a direct assessment start until that modules videos are complete`() {
        val runner = CourseAutomationSession("s", answerAssessments = true, assessmentMode = AssessmentMode.Review).apply { configureAnswers(bank) }
        runner.read(page(CourseElement("start", label = "Start Assessment"), assessment = CourseAssessment("Wellness", start = "start")), 0)
        assertNull(runner.action)
        assertNull(runner.approval)
        assertTrue(runner.detail.contains("videos are complete"))
    }

    @Test
    fun `given completed videos and exact answer then confirm selection submit once and await pass before dashboard`() {
        val runner = ready(5000)
        val assessment = CourseAssessment("Wellness", listOf(q), submit = "submit")
        runner.read(page(submit, assessment = assessment), 3000)
        assertNull(runner.action, "Preserve post-click wait")
        runner.read(page(submit, assessment = assessment), 7000)
        assertEquals("assessmentAnswer", runner.action?.kind)
        assertEquals("b", runner.action?.element)
        runner.acknowledge(runner.action!!.request, true, 7000)
        runner.read(page(submit, assessment = assessment), 12000)
        assertNull(runner.action, "An acknowledgement alone cannot confirm radio selection")
        val selected = assessment.copy(questions = listOf(q.copy(options = q.options.map { it.copy(selected = it.id == "b") })))
        runner.read(page(submit, assessment = selected), 13000)
        assertNull(runner.action)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(page(submit, assessment = selected), 13000)
        assertEquals("assessmentSubmit", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 13000)
        runner.read(page(submit, home, assessment = selected), 18000)
        assertNull(runner.action, "No repeated submission or early dashboard return")
        assertFalse(runner.completed)
        runner.read(page(home, assessment = selected.copy(result = "passed")), 19000)
        assertEquals("home", runner.action?.element)
        assertEquals("assessmentReturn", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 19000)
        runner.read(CourseSnapshot("s", "doc", "/dashboard", elements = listOf(
            CourseElement("all", tag = "h2", label = "All Courses"),
            CourseElement("next-module", href = "/dashboard/courses/second", context = "75% Min. Pass 96min Duration"))), 24000)
        assertEquals("next-module", runner.action?.element)
    }

    @Test
    fun `given unmatched questions missing modules or a failed result then no guessing submission or retry occurs`() {
        val runner = ready()
        val unknown = CourseAssessment("Wellness", listOf(q.copy(text = "Different question")), submit = "submit")
        runner.read(page(submit, assessment = unknown), 3000)
        assertNull(runner.action)
        assertTrue(runner.detail.contains("no exact saved answer"))
        assertNotNull(runner.attention)
        runner.read(page(submit, assessment = unknown.copy(module = "Missing")), 4000)
        assertNull(runner.action)
        runner.read(page(submit, assessment = unknown.copy(result = "failed")), 5000)
        assertNull(runner.action)
        assertTrue(runner.detail.contains("not be retried"))
    }

    @Test
    fun `direct assessment entry answers but child frames cannot select answers`() {
        val runner = CourseAutomationSession("s", answerAssessments = true).apply { configureAnswers(bank) }
        runner.read(page(submit, assessment = CourseAssessment("Wellness", listOf(q))), 0)
        assertEquals("assessmentAnswer",runner.action?.kind)
        val ready = ready()
        ready.read(page(submit, assessment = CourseAssessment("Wellness", listOf(q)), top = false), 3000)
        assertNull(ready.action)
    }

    @Test
    fun `given assessment opt in when edited then it persists without changing legacy defaults`() {
        val routine = CourseAutomationRoutine("a", "Course", answerAssessments = true)
        assertEquals(routine, routine.toDraft().toRoutine("a"))
        assertFalse(CourseAutomationRoutine("old", "Course").answerAssessments)
        assertNotNull(CourseAnswerBank.decode(fixture))
    }

    @Test
    fun `given paginated assessment then every matched question must be observed before final submission`() {
        val first = """{"question":"First?","options":["A","B"],"answer":"B"}"""
        val second = """{"question":"Second?","options":["A","B"],"answer":"A"}"""
        val runner = ready()
        runner.configureAnswers(CourseAnswerBank.decode("""{"Wellness":[$first,$second]}"""))
        val options = listOf(CourseOption("a", "A"), CourseOption("b", "B", selected = true))
        val next = CourseElement("next-question", label = "Next Question")
        runner.read(page(next, submit, assessment = CourseAssessment("Wellness", listOf(CourseQuestion("q1", "First?", options)), next = next.id, submit = submit.id)), 3000)
        assertEquals("assessmentNext", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true, 3000)
        val last = CourseAssessment("Wellness", listOf(CourseQuestion("q2", "Second?", options.map { it.copy(selected = it.id == "a") })), submit = submit.id)
        runner.read(page(submit, assessment = last), 4000)
        assertNull(runner.action)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(page(submit, assessment = last), 4000)
        assertEquals("assessmentSubmit", runner.action?.kind)
    }

    @Test
    fun `given live test route and repeated question text then separate instances count and End Test requires approval`() {
        val runner = ready()
        val selected = q.copy(options = q.options.map { it.copy(selected = it.id == "b") })
        val next = CourseElement("next", label = "Next")
        fun testPage(question: CourseQuestion, last: Boolean = false) = CourseSnapshot("s", "doc", "$course/test/attempt1",
            elements = listOf(next, CourseElement("end", label = "End Test")),
            assessment = CourseAssessment("Wellness", listOf(question), next = if (last) null else "next", submit = "end", totalQuestions = 2))
        runner.read(testPage(selected.copy(id = "q0")), 3000)
        assertEquals("assessmentNext", runner.action?.kind)
        runner.acknowledge(runner.action!!.request, true)
        runner.read(testPage(selected.copy(id = "q1"), last = true), 4000)
        assertNull(runner.action)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
        runner.approveAssessment(runner.approval!!.token)
        runner.read(testPage(selected.copy(id = "q1"), last = true), 4001)
        assertEquals("assessmentSubmit", runner.action?.kind)
        assertEquals("end", runner.action?.element)
    }

    @Test
    fun `given review mode resumes a partly completed assessment then it asks the user to review before submission`() {
        val runner = CourseAutomationSession("s", answerAssessments = true, assessmentMode = AssessmentMode.Review)
        runner.configureAnswers(bank)
        val selected = q.copy(options = q.options.map { it.copy(selected = it.id == "b") })

        runner.read(page(submit, assessment = CourseAssessment("Wellness", listOf(selected), submit = "submit", totalQuestions = 2)), 0)

        assertNull(runner.action)
        assertEquals(CourseApprovalKind.SubmitAssessment, runner.approval?.kind)
        assertTrue(runner.detail.contains("Review Wellness"))
    }

    @Test
    fun `given real Start Assessment confirmation then approved attempt can proceed without touching course navigation`() {
        val runner = ready()
        val start = CourseElement("start", label = "Start Assessment")
        runner.read(page(start, assessment = CourseAssessment("Wellness", start = "start")), 3000)
        assertNull(runner.approval, "The approval was already required before opening this module's assessment")
        assertEquals("assessmentStart", runner.action?.kind)
        assertEquals("start", runner.action?.element)
    }

    @Test
    fun `given a passed assessment without a certificate then ask for manual download before returning`() {
        val stored = mutableSetOf<CourseCertificateTarget>()
        val certificates = object : CourseCertificateStore {
            override fun hasCertificate(target: CourseCertificateTarget) = target in stored
            override fun prepareDownload(target: CourseCertificateTarget) = Unit
            override fun collectDownloaded(target: CourseCertificateTarget) = target in stored
            override fun confirmDownloaded(target: CourseCertificateTarget) { stored += target }
            override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
        }
        val runner = CourseAutomationSession("s", answerAssessments = true)
        runner.configureCertificates("Jagdeep", certificates)
        val certificate = CourseElement("certificate", label = "View certificate")
        val passed = CourseAssessment("Wellness", result = "passed", certificate = certificate.id)

        runner.read(page(certificate, assessment = passed), 0)

        assertNull(runner.action)
        assertEquals(CourseApprovalKind.CertificateDownloaded, runner.approval?.kind)
        runner.approveAssessment(requireNotNull(runner.approval).token)
        assertTrue(stored.contains(CourseCertificateTarget("Jagdeep", "Wellness")))
        runner.read(page(home, assessment = passed), 1)
        assertEquals("assessmentReturn", runner.action?.kind)
    }

    @Test
    fun `given automatic certificate downloads then click the certificate control before continuing`() {
        val certificates = object : CourseCertificateStore {
            override fun hasCertificate(target: CourseCertificateTarget) = false
            override fun prepareDownload(target: CourseCertificateTarget) = Unit
            override fun collectDownloaded(target: CourseCertificateTarget) = false
            override fun confirmDownloaded(target: CourseCertificateTarget) = Unit
            override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
        }
        val runner = CourseAutomationSession("s", answerAssessments = true, autoDownloadCertificates = true)
        runner.configureCertificates("Jagdeep", certificates)
        val certificate = CourseElement("certificate", label = "View certificate")
        runner.read(page(certificate, assessment = CourseAssessment("Wellness", result = "passed", certificate = certificate.id)), 0)

        assertEquals("certificateDownload", runner.action?.kind)
        assertEquals("certificate", runner.action?.element)
        assertNull(runner.approval)
    }

    @Test
    fun `given a saved certificate preview then close it before returning`() {
        val stored = mutableSetOf<CourseCertificateTarget>()
        val certificates = object : CourseCertificateStore {
            override fun hasCertificate(target: CourseCertificateTarget) = target in stored
            override fun prepareDownload(target: CourseCertificateTarget) = Unit
            override fun collectDownloaded(target: CourseCertificateTarget) = target in stored
            override fun confirmDownloaded(target: CourseCertificateTarget) { stored += target }
            override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
        }
        val runner = CourseAutomationSession("s", answerAssessments = true)
        runner.configureCertificates("Jagdeep", certificates)
        val target = CourseCertificateTarget("Jagdeep", "Wellness")
        stored += target
        val close = CourseElement("close", label = "Close")
        runner.read(page(close, assessment = CourseAssessment("Wellness", result = "passed", certificateClose = close.id)), 0)

        assertEquals("certificateClose", runner.action?.kind)
    }

    @Test
    fun `given passed result keeps one manual certificate confirmation open`() {
        val stored = mutableSetOf<CourseCertificateTarget>()
        val certificates = object : CourseCertificateStore {
            override fun hasCertificate(target: CourseCertificateTarget) = target in stored
            override fun prepareDownload(target: CourseCertificateTarget) = Unit
            override fun collectDownloaded(target: CourseCertificateTarget) = false
            override fun confirmDownloaded(target: CourseCertificateTarget) { stored += target }
            override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
        }
        val runner = CourseAutomationSession("s", answerAssessments = true)
        runner.configureCertificates("Jagdeep", certificates)
        val certificate = CourseElement("certificate", label = "View certificate")
        val passed = CourseAssessment("Wellness", result = "passed", certificate = certificate.id)

        runner.read(page(certificate, assessment = passed), 0)
        assertEquals(CourseApprovalKind.CertificateDownloaded, runner.approval?.kind)
        val token = requireNotNull(runner.approval).token
        runner.read(page(certificate, assessment = passed), 1)
        assertEquals(token, runner.approval?.token)
        assertNull(runner.action)
        runner.approveAssessment(requireNotNull(runner.approval).token)
        runner.read(page(home, assessment = passed), 2)
        assertEquals("assessmentReturn", runner.action?.kind)
    }

    @Test
    fun `given Windows does not expose the certificate button then ask for manual confirmation`() {
        val stored = mutableSetOf<CourseCertificateTarget>()
        val certificates = object : CourseCertificateStore {
            override fun hasCertificate(target: CourseCertificateTarget) = target in stored
            override fun prepareDownload(target: CourseCertificateTarget) = Unit
            override fun collectDownloaded(target: CourseCertificateTarget) = false
            override fun confirmDownloaded(target: CourseCertificateTarget) { stored += target }
            override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
        }
        val runner = CourseAutomationSession("s", answerAssessments = true)
        runner.configureCertificates("Jagdeep", certificates)
        val passed = CourseAssessment("Wellness", result = "passed")
        val resultPage = page(assessment = passed)

        runner.read(resultPage, 0)
        assertEquals(CourseApprovalKind.CertificateDownloaded, runner.approval?.kind)
        runner.approveAssessment(requireNotNull(runner.approval).token)
        assertTrue(stored.contains(CourseCertificateTarget("Jagdeep", "Wellness")))
        runner.read(page(home, assessment = passed), 20_002)
        assertEquals("assessmentReturn", runner.action?.kind)
    }
}
