package com.jagdeep.home.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.HotKey
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.ClickPoint
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.ClickTarget
import com.jagdeep.home.domain.FakeAutomation
import com.jagdeep.home.domain.FakePageRunner
import com.jagdeep.home.domain.CourseAutomationRoutine
import com.jagdeep.home.domain.RoutineKind
import com.jagdeep.home.domain.ScrollClickRoutine
import com.jagdeep.home.domain.FakeRoutineRepository
import com.jagdeep.home.domain.FakeRunHistory
import com.jagdeep.home.domain.HomeViewModel
import com.jagdeep.home.domain.RoutineEditorViewModel
import com.jagdeep.home.domain.RoutineLibrary
import com.jagdeep.home.domain.RunLimit
import com.jagdeep.home.ui.screens.HomeScreen
import com.jagdeep.home.ui.screens.RoutineEditorScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class HomeScreensUiTest {
    @Test fun `DGS options expose hours default speed and reserved bank and save without starting`() = runDesktopComposeUiTest(1000,800) {
        val repo=FakeRoutineRepository(RoutineLibrary())
        val vm=RoutineEditorViewModel(null,controller(),repo,{"dgs"},RoutineKind.DgsELearning)
        setContent { AppTheme { RoutineEditorScreen(vm,onBack={}) } }
        onNodeWithText("Required course hours").performTextInput("18")
        onNodeWithText("Hours already credited by DGS").assertExists()
        onNodeWithText("2×").performScrollTo().assertIsDisplayed()
        onNodeWithText("Save").performClick()
        waitUntil { vm.state.value.isSaved }
        runOnIdle {
            val saved=repo.library.value!!.selected as com.jagdeep.home.domain.DgsELearningRoutine
            assertEquals(1.0,saved.playbackSpeed)
            assertEquals(com.jagdeep.shared.answers.DEFAULT_DGS_ANSWER_BANK_FILE,saved.answerBankFile)
        }
    }

    @Test fun `routine chooses a named bank and validates without starting a run`() = runDesktopComposeUiTest(1000,800) {
        var validated: String? = null
        var opened = 0
        val files=object : com.jagdeep.shared.answers.AnswerBankFiles {
            override val available=true
            override val folderPath="/example/answer-banks"
            override suspend fun names()=listOf("fleet.json")
            override suspend fun validate(name:String):String {validated=name;return "Valid file"}
            override suspend fun openFolder():String? { opened++;return null }
        }
        val vm=RoutineEditorViewModel(null,controller(),FakeRoutineRepository(RoutineLibrary()),{"new"},RoutineKind.CourseAutomation,files)
        setContent { AppTheme { RoutineEditorScreen(vm,onBack={}) } }
        onNodeWithText("File name").assertExists()
        waitUntil { !vm.state.value.answerBankBusy }
        onNodeWithText("Choose saved file").performScrollTo().performClick()
        onNodeWithText("fleet.json").performClick()
        onNodeWithText("Validate and reload list").performScrollTo().performClick()
        waitUntil { validated=="fleet.json" }
        onNodeWithText("Valid file").assertExists()
        runOnIdle { assertEquals("fleet.json",vm.state.value.draft.answerBankFile) }
        onNodeWithText("Save").assertIsDisplayed()
        onNodeWithText("Open Q&A folder").performScrollTo().performClick()
        waitUntil { opened == 1 }
    }

    @Test
    fun `speed selection saves through production repository and reopens at one times`() = runDesktopComposeUiTest(1000,700) {
        val store = com.jagdeep.persistence.preferences.InMemoryPreferencesStore("")
        val repository = com.jagdeep.home.data.PreferencesRoutineRepository(store,scope)
        runBlocking { repository.save(CourseAutomationRoutine("speed","Speed",playbackSpeed=2.0)) }
        val vm = RoutineEditorViewModel("speed",controller(),repository,{"new"})
        setContent { AppTheme { RoutineEditorScreen(vm,onBack={}) } }
        waitUntil { !vm.state.value.isLoading }
        onNodeWithText("Save").assertIsDisplayed()
        onNodeWithText("1×").performScrollTo().performClick()
        onNodeWithText("Save").assertIsDisplayed().performClick()
        waitUntil { vm.state.value.isSaved }
        val reloaded = com.jagdeep.home.data.PreferencesRoutineRepository(store,scope)
        val reopened = RoutineEditorViewModel("speed",controller(),reloaded,{"new"})
        waitUntil { !reopened.state.value.isLoading }
        runOnIdle { assertEquals(1.0,reopened.state.value.draft.playbackSpeed) }
        val fresh = RoutineEditorViewModel(null,controller(),reloaded,{"new"},newKind=RoutineKind.CourseAutomation)
        waitUntil { !fresh.state.value.isLoading }
        runOnIdle { assertEquals(1.0,fresh.state.value.draft.playbackSpeed) }
    }

    @Test
    fun `completed video pass shows confirmation and only explicit begin approves assessments`() = runDesktopComposeUiTest(1000,900) {
        val page = FakePageRunner()
        var approved: String? = null
        val runner = object : com.jagdeep.home.domain.PageRunner by page {
            override fun approveAssessment(token: String) {
                approved = token
                page.state.value = page.state.value.copy(activity=(page.state.value.activity as com.jagdeep.home.domain.PageActivity.Automation).copy(approval=null))
            }
        }
        val routine = CourseAutomationRoutine("course","Course",answerAssessments=true,learningOrder=com.jagdeep.home.domain.LearningOrder.VideosFirst)
        val library = FakeRoutineRepository(RoutineLibrary(listOf(routine),"course"))
        val controller = ClickController(automation,library,MutableStateFlow(AppPreferences()),FakeRunHistory(),scope,{System.currentTimeMillis()},{"run"},page=runner)
        fun pending(token: String) = com.jagdeep.home.domain.PageActivity.Automation(routine,"All videos finished",approval=com.jagdeep.home.domain.CourseApprovalRequest(token,com.jagdeep.home.domain.CourseApprovalKind.StartAssessmentPass,"Unfinished assessments"))
        page.state.value = page.state.value.copy(activity=pending("pass"))
        val viewModel = HomeViewModel(controller,library)
        setContent { AppTheme { androidx.compose.material3.Surface { com.jagdeep.home.ui.screens.RunningRoutineScreen(viewModel) {} } } }
        onNodeWithText("Begin assessments").assertExists()
        runOnIdle { assertEquals(null,approved) }
        onNodeWithText("Begin assessments").performClick()
        waitUntil { approved == "pass" }
        runOnIdle { approved=null; page.state.value=page.state.value.copy(activity=pending("another")) }
        onNodeWithText("Not now — stop routine").performClick()
        waitUntil { page.state.value.activity == null }
        runOnIdle { assertEquals(null,approved) }
    }

    @Test
    fun `videos first choice enables assessment settings and can return to per module flow`() = runDesktopComposeUiTest(1000,1200) {
        val viewModel = RoutineEditorViewModel(null,controller(),FakeRoutineRepository(RoutineLibrary()),newId={"new"},newKind=RoutineKind.CourseAutomation)
        setContent { AppTheme { RoutineEditorScreen(viewModel,onBack={}) } }
        onNodeWithText("Watch all videos first").performScrollTo().performClick()
        onNodeWithText("Handle module assessments").assertExists()
        runOnIdle {
            assertEquals(com.jagdeep.home.domain.LearningOrder.VideosFirst,viewModel.state.value.draft.learningOrder)
            assertTrue(viewModel.state.value.draft.answerAssessments)
        }
        onNodeWithText("Assessment after each module").performScrollTo().performClick()
        onNodeWithText("Handle module assessments").assertExists()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val automation = FakeAutomation()
    private val atPoints = ClickRoutine(
        id = "points",
        name = "Chrome button",
        target = ClickTarget.AtPoints(listOf(ClickPoint(100, 200), ClickPoint(300, 400))),
        intervalMillis = 60_000,
        startDelaySeconds = 0,
        limit = RunLimit.Clicks(500),
        onlyInApp = "Google Chrome",
    )
    private val atPointer = ClickRoutine("pointer", "Click at the pointer", startDelaySeconds = 0, intervalMillis = 60_000)
    private val repository = FakeRoutineRepository(RoutineLibrary(listOf(atPointer, atPoints), "pointer"))

    private fun controller() = ClickController(
        automation, repository, MutableStateFlow(AppPreferences()), FakeRunHistory(), scope, { System.currentTimeMillis() }, { "run" },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `given course selected on home then setup and saved progress links stay out of the home screen`() = runDesktopComposeUiTest(1000, 800) {
        val routine=com.jagdeep.home.domain.CourseAutomationRoutine("course","Course")
        val library=FakeRoutineRepository(RoutineLibrary(listOf(routine),"course"))
        val viewModel=HomeViewModel(controller(),library)
        setContent { AppTheme { HomeScreen(viewModel,appIcon={},onNewRoutine={},onEditRoutine={}) } }
        onNodeWithText("Ready").assertExists()
        onNodeWithText("Setup and how it works").assertDoesNotExist()
        onNodeWithText("Saved progress by user").assertDoesNotExist()
    }

    @Test
    fun `given course routine options then setup and saved user history can be opened there`() = runDesktopComposeUiTest(1000, 900) {
        val routine=com.jagdeep.home.domain.CourseAutomationRoutine("course","Course")
        val library=FakeRoutineRepository(RoutineLibrary(listOf(routine),"course"))
        val page=FakePageRunner()
        page.state.value=page.state.value.copy(progressAccounts=listOf(com.jagdeep.persistence.room.learning.LearningAccount("user","Example User",1000)))
        var loaded: String? = null
        val runner=object : com.jagdeep.home.domain.PageRunner by page {
            override fun loadProgressHistory(account: String) {
                loaded=account
                page.state.value=page.state.value.copy(savedProgress=com.jagdeep.home.domain.CourseProgressSummary(account,"Example User",1000,listOf(
                    com.jagdeep.persistence.room.learning.LearningRecord(account,"attempt","Wellness","assessment","Course Assessment","passed",90.0,observedAt=1000))))
            }
        }
        val controller=ClickController(automation,library,MutableStateFlow(AppPreferences()),FakeRunHistory(),scope,{System.currentTimeMillis()},{"id"},page=runner)
        val viewModel=RoutineEditorViewModel("course",controller,library,{"id"})
        setContent { AppTheme { RoutineEditorScreen(viewModel,onBack={}) } }
        onNodeWithText("Setup and how it works").performScrollTo().performClick()
        onNodeWithText("Sagar Mein Yog automation guide").assertExists()
        onNodeWithText("Got it").performClick()
        onNodeWithText("Saved progress by user").performScrollTo().performClick()
        onNodeWithText("Example User · user").performClick()
        onNodeWithText("assessment: passed · 90.0%",substring=true).assertExists()
        runOnIdle { assertEquals("user",loaded) }
        onNodeWithText("Close").performClick()
    }

    @Test
    fun `given course needs submission approval then running screen shows details and routes the approval`() = runDesktopComposeUiTest(1000, 900) {
        val page=FakePageRunner()
        var approved: String? = null
        val runner=object : com.jagdeep.home.domain.PageRunner by page {
            override fun approveAssessment(token: String) { approved=token }
        }
        val routine=com.jagdeep.home.domain.CourseAutomationRoutine("course","Course")
        val library=FakeRoutineRepository(RoutineLibrary(listOf(routine),"course"))
        val controller=ClickController(automation,library,MutableStateFlow(AppPreferences()),FakeRunHistory(),scope,{System.currentTimeMillis()},{"run"},page=runner)
        page.state.value=page.state.value.copy(activity=com.jagdeep.home.domain.PageActivity.Automation(routine,"All answers reviewed",clicks=12,
            approval=com.jagdeep.home.domain.CourseApprovalRequest("approval",com.jagdeep.home.domain.CourseApprovalKind.SubmitAssessment,"Wellness"),
            context=com.jagdeep.home.domain.CourseRunContext(module="Wellness",question="Which option?")))
        val viewModel=HomeViewModel(controller,library)
        setContent { AppTheme { androidx.compose.material3.Surface { com.jagdeep.home.ui.screens.RunningRoutineScreen(viewModel) {} } } }
        onNodeWithText("All answers reviewed").assertExists()
        onNodeWithText("Needs your attention").assertExists()
        onNodeWithText("Current module: Wellness").assertExists()
        onNodeWithText("Setup and how it works").assertDoesNotExist()
        onNodeWithText("Saved progress by user").assertDoesNotExist()
        onNodeWithText("Current activity").assertExists()
        onNodeWithText("Run progress").assertDoesNotExist()
        onNodeWithText("Which option?").assertExists()
        saveShot("running-grouped")
        onNodeWithText("Approve submission").performClick()
        runOnIdle { assertEquals("approval",approved) }
    }

    @Test
    fun `given an active routine then home only offers a link to its running screen`() = runDesktopComposeUiTest(1000, 800) {
        val viewModel = HomeViewModel(controller(), repository)
        var opened = false
        setContent { AppTheme(darkTheme = false) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = {}, onEditRoutine = {}, onOpenRunning = { opened = true }) } }
        onNodeWithText("Ready").assertExists()
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { automation.clicks.isNotEmpty() }
        onNodeWithText("A routine is running").assertExists()
        onNodeWithText("Clicking").assertDoesNotExist()
        onNodeWithContentDescription("Edit Chrome button").assertIsNotEnabled()
        onNodeWithText("View running routine").performClick()
        runOnIdle { assertTrue(opened); viewModel.stop() }
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.lastRun != null }
        onNodeWithText("Ready").assertExists()
    }

    @Test
    fun `given dedicated running screen then show status stop and a finished summary without routine list`() = runDesktopComposeUiTest(1000, 800) {
        val viewModel = HomeViewModel(controller(), repository)
        var back = false
        setContent { AppTheme { com.jagdeep.home.ui.screens.RunningRoutineScreen(viewModel) { back = true } } }
        waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.isLoading }
        runOnIdle { viewModel.start() }
        waitUntil(timeoutMillis = 5_000) { automation.clicks.isNotEmpty() }
        onNodeWithText("Clicking").assertExists()
        onNodeWithText("Routine type").performScrollTo()
        onNodeWithText("Stop").assertIsDisplayed()
        onNodeWithContentDescription("Edit Chrome button").assertDoesNotExist()
        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.lastRun != null }
        onNodeWithText("Run finished").assertExists()
        onNodeWithText("Stopped by you").assertExists()
        onNodeWithText("Back to routines").performClick()
        runOnIdle { assertTrue(back) }
    }

    @Test
    fun `given macOS hasn't allowed input when shown then the next step is allowing it`() = runDesktopComposeUiTest(1000, 800) {
        automation.inputAccess = InputAccess.NeedsPermission
        val viewModel = HomeViewModel(controller(), repository)
        setContent { AppTheme(darkTheme = true) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = {}, onEditRoutine = {}) } }
        onNodeWithText("Allow access to click").assertExists()
        saveShot("home-needs-access")

        onNodeWithText("Allow access").performClick()
        waitForIdle()
        assertEquals(1, automation.accessRequests)
        onNodeWithText("Start").assertDoesNotExist()
    }

    @Test
    fun `given no routines when New routine is pressed then it asks for the kind and opens that one`() = runDesktopComposeUiTest(1000, 800) {
        val empty = FakeRoutineRepository(RoutineLibrary())
        var opened: RoutineKind? = null
        val viewModel = HomeViewModel(controller(), empty)
        setContent { AppTheme(darkTheme = false) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = { opened = it }, onEditRoutine = {}) } }
        onNodeWithText("No routines yet").assertExists()
        onNodeWithText("New routine").performClick()
        RoutineKind.entries.forEach { onNodeWithText(it.label).assertExists() }
        onNodeWithText("Web page helper").assertDoesNotExist()
        onNodeWithText("Course helper").assertDoesNotExist()
        onNodeWithText("Sagar Mein Yog automation").assertExists()
        onNodeWithText(RoutineKind.ScrollClick.label).performClick()
        assertEquals(RoutineKind.ScrollClick, opened)
    }

    @Test
    fun `given routines of every kind when listed then each shows its kind`() = runDesktopComposeUiTest(1000, 800) {
        val mixed = FakeRoutineRepository(
            RoutineLibrary(listOf(atPointer, ScrollClickRoutine("s", "Next pages", ClickPoint(640, 880)), CourseAutomationRoutine("h", "Course")), "s"),
        )
        val files = object : com.jagdeep.home.domain.RoutineFiles {
            override suspend fun save(suggestedName: String, contents: String): String? = null
            override suspend fun open(): String? = null
        }
        val viewModel = HomeViewModel(controller(), mixed, files)
        setContent { AppTheme(darkTheme = false) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = {}, onEditRoutine = {}) } }
        onNodeWithText("Import").assertDoesNotExist()
        onNodeWithText("Export all").assertDoesNotExist()
        onNodeWithText("Scroll and click · at 640, 880 · 20 rounds · stops for videos").assertExists()
        onNodeWithText("Sagar Mein Yog automation · sagarmeinyog.com · automatically watches unfinished video lessons").assertExists()
        onNodeWithContentDescription("Export Next pages").assertDoesNotExist()
        saveShot("home-kinds")
    }


    @Test
    fun `given a multi-point click when a step is opened then it can be changed and picked again`() = runDesktopComposeUiTest(1000, 1700) {
        val viewModel = RoutineEditorViewModel("points", controller(), repository, newId = { "new" })
        setContent { AppTheme(darkTheme = false) { androidx.compose.material3.Surface { RoutineEditorScreen(viewModel, onBack = {}) } } }
        onNodeWithText("Point 1").assertExists()
        onNodeWithText("Left click at 300, 400 · waits 1 min").assertExists()
        onNodeWithText("Point 2").performClick()
        onNodeWithText("Right").performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft.points[1].button == com.jagdeep.automation.PointerButton.Right }
        onNodeWithText("Right click at 300, 400 · waits 1 min").assertExists()
        assertEquals("1" to com.jagdeep.home.domain.IntervalUnit.Minutes, viewModel.state.value.draft.points[1].let { it.delay to it.delayUnit })
        saveShot("editor-steps")

        onNodeWithText("Pick again").performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.capture?.replacing == 1 }
        Thread.sleep(200) // Lets the capture collector subscribe on the real dispatcher.
        automation.position = ScreenPoint(7, 8)
        automation.keyPresses.tryEmit(HotKey.F7)
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft.points[1].x == "7" }
        assertEquals(2, viewModel.state.value.draft.points.size, "Picking again replaces the spot")
        assertEquals(com.jagdeep.automation.PointerButton.Right, viewModel.state.value.draft.points[1].button)
    }

    @Test
    fun `given a new scroll and click routine when saved without a spot then it asks for one`() = runDesktopComposeUiTest(1000, 1200) {
        val empty = FakeRoutineRepository(RoutineLibrary())
        val viewModel = RoutineEditorViewModel(null, controller(), empty, newId = { "new" }, newKind = RoutineKind.ScrollClick)
        setContent { AppTheme(darkTheme = false) { RoutineEditorScreen(viewModel, onBack = {}) } }
        onNodeWithText("Pick steps on screen").assertExists()
        saveShot("editor-scroll-click")
        onNodeWithText("Save").performClick()
        onNodeWithText("Add at least one step to click at the bottom.").assertExists()
        assertTrue(empty.library.value!!.routines.isEmpty())
    }


    @Test
    fun `given a blank name when saving in the editor then the error shows and nothing is saved`() = runDesktopComposeUiTest(1000, 1700) {
        val viewModel = RoutineEditorViewModel("points", controller(), repository, newId = { "new" })
        setContent { AppTheme(darkTheme = false) { RoutineEditorScreen(viewModel, onBack = {}) } }
        saveShot("editor")
        viewModel.updateDraft { it.copy(name = " ") }
        onNodeWithText("Save").performClick()
        onNodeWithText("Enter a name.").assertExists()
        assertEquals("Chrome button", runBlocking { repository.library.value!!.routines.last().name })
    }

    @Test
    fun `given picking on screen when the hot key is pressed then the point appears in the editor`() = runDesktopComposeUiTest(1000, 1700) {
        val viewModel = RoutineEditorViewModel("points", controller(), repository, newId = { "new" })
        setContent { AppTheme(darkTheme = true) { RoutineEditorScreen(viewModel, onBack = {}) } }
        onNodeWithText("Pick more on screen").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.capture != null }
        Thread.sleep(200) // Lets the capture collector subscribe on the real dispatcher.
        automation.position = ScreenPoint(640, 480)
        automation.keyPresses.tryEmit(HotKey.F7)
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft.points.size == 3 }
        onNodeWithText("Done (1 added)").assertExists()
        saveShot("editor-picking")
    }
}

/** Set UI_TEST_SHOT_DIR to save what a test rendered, for reviewing a layout by eye. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}

