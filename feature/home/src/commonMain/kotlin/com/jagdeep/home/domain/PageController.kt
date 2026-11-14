package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.logger.Log
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.shared.live.LiveSource
import com.jagdeep.shared.live.NoLiveLog
import com.jagdeep.shared.live.log
import com.jagdeep.shared.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class CourseRunContext(val module: String? = null, val lesson: String? = null, val question: String? = null)

/** The Chrome routine that's running. */
sealed interface PageActivity {
    val routine: Routine

    data class Dgs(override val routine: DgsELearningRoutine, val progress: DgsProgress) : PageActivity

    data class ScrollClick(override val routine: ScrollClickRoutine, val progress: ScrollClickProgress) : PageActivity

    data class Automation(
        override val routine: CourseAutomationRoutine,
        val detail: String,
        val clicks: Long = 0,
        val approval: CourseApprovalRequest? = null,
        val manualAnswer: ManualAnswerRequest? = null,
        val failure: AssessmentFailureRequest? = null,
        val context: CourseRunContext = CourseRunContext(),
        val progressProfile: ProgressProfileRequest? = null,
        val progressSummary: CourseProgressSummary? = null,
        val modules: List<CourseModuleStatus> = emptyList(),
        val moduleAction: CourseModuleAction? = null,
        val savingAnswer: Boolean = false,
        val answerSaveError: String? = null,
    ) : PageActivity
}

data class PageState(
    val progressRevision: Long = 0,
    val inactivityWarning: String? = null,
    val extension: com.jagdeep.shared.browser.BrowserExtensionStatus = com.jagdeep.shared.browser.BrowserExtensionStatus(),
    val isSupported: Boolean = true,
    val isConnected: Boolean = false,
    val problem: String? = null,
    val isScrolling: Boolean = false,
    /** The label of the button highlighted in Chrome right now. */
    val foundLabel: String? = null,
    val activity: PageActivity? = null,
    val progressAccounts: List<com.jagdeep.persistence.room.learning.LearningAccount> = emptyList(),
    val savedProgress: CourseProgressSummary? = null,
    val savedProgressProfiles: List<CourseProgressSummary> = emptyList(),
    val progressHistoryError: String? = null,
)

/** Runs the routines that work inside Chrome. [ClickController] uses it so only one routine runs at a time. */
interface PageRunner {
    val state: StateFlow<PageState>

    /** The most recent Chrome routine finished since the app opened. */
    val lastRun: StateFlow<RunRecord?>

    val isActive: Boolean get() = state.value.activity != null

    /** Starts a Chrome routine. Returns why it can't start, or null when it started. */
    fun start(routine: Routine): String?

    /** Returns true when there was a routine to stop. */
    fun stop(): Boolean

    /** Stops a running scroll-and-click routine using its secondary shortcut. */
    fun toggleScrolling()
    fun approveAssessment(token: String) = Unit
    fun confirmManualAnswer(token: String, save: Boolean) = Unit
    fun loadProgressHistory(account: String) = Unit
    fun loadAllProgressHistory() = Unit
    fun refreshCourseProgress() = Unit
    fun confirmProgressProfile(token: String, localName: String) = Unit
    fun saveReviewedAnswers(token: String, answers: Map<String, String>) = Unit
    fun retryAssessment(token: String, automatic: Boolean) = Unit
    fun runCourseModule(module: String, action: CourseModuleAction) = Unit
    fun stopCourseModule() = Unit
    fun selectDgsCourse(id: String) = Unit
}

/** Where there's no Chrome link, as in tests of other routines. */
object NoPageRunner : PageRunner {
    override val state: StateFlow<PageState> = MutableStateFlow(PageState(isSupported = false)).asStateFlow()
    override val lastRun: StateFlow<RunRecord?> = MutableStateFlow<RunRecord?>(null).asStateFlow()
    override fun start(routine: Routine): String = "This kind of routine works with Chrome on a computer."
    override fun stop() = false
    override fun toggleScrolling() = Unit
    override fun runCourseModule(module: String, action: CourseModuleAction) = Unit
    override fun stopCourseModule() = Unit
}

/** Shared extension command. Retired helper fields stay inert for older installed extensions. */
@Serializable
internal data class HelperCommand(
    val enabled: Boolean,
    val scrolling: Boolean,
    val pixelsPerSecond: Int,
    val words: List<String>,
    val advance: Long,
    /** The app clicks by itself in this mode, so the extension reports where the page is. */
    val autoRun: Boolean = false,
    val watchVideos: Boolean = false,
    /** During a scroll and click run: the button text to click, once, for request [clickRequest]. */
    val clickText: String? = null,
    val clickRequest: Long = 0,
    /** Always false; retained until older installed extensions have been reloaded. */
    val course: Boolean = false,
    val courseAutomation: CourseCommand? = null,
    val dgs: DgsCommand? = null,
)

@Serializable
internal data class HelperEvent(
    val version: String? = null,
    val progress: String? = null,
    val type: String,
    val label: String? = null,
    val focused: Boolean = false,
    val atBottom: Boolean = false,
    val signature: String? = null,
    val video: String? = null,
    val request: Long? = null,
)

internal const val BROWSER_APP_NAME = "Google Chrome"

/**
 * Process-scoped, because its keys have to work while Chrome is in front. The extension is told to
 * act only while a Chrome routine runs. Its own clicks each follow a press of the user's key; a scroll
 * and click routine clicks through the operating system at the spot the user picked.
 */
class PageController(
    private val automation: DesktopAutomation,
    private val bridge: LocalBridge,
    preferences: Flow<AppPreferences>,
    private val scope: CoroutineScope,
    private val history: RunHistoryStore,
    private val now: () -> Long,
    private val newId: () -> String,
    private val live: LiveLog = NoLiveLog,
    private val answerBankStore: CourseAnswerBankStore = NoCourseAnswerBankStore,
    private val assessmentAlerts: AssessmentAlerts = AssessmentAlerts { automation.playAlert() },
    private val progressRepository: CourseProgressRepository = NoCourseProgressRepository,
    private val certificateStore: CourseCertificateStore = NoCourseCertificateStore,
    private val dgsStudyRepository:DgsStudyRepository = NoDgsStudyRepository,
    private val dgsElapsedMillis:()->Long = now,
    extensionStatus: StateFlow<com.jagdeep.shared.browser.BrowserExtensionStatus> = MutableStateFlow(com.jagdeep.shared.browser.BrowserExtensionStatus()),
) : PageRunner {
    private var historySelection = ""
    private var lastCourseSnapshot: CourseSnapshot? = null
    private val progressMarkers = mutableMapOf<String, String>()
    private fun observeProgress(channel: String, marker: String) {
        if (progressMarkers[channel] == marker) return
        if (channel !in progressMarkers && progressMarkers.size >= 32) progressMarkers.remove(progressMarkers.keys.first())
        progressMarkers[channel] = marker
        mutableState.update { it.copy(progressRevision = it.progressRevision + 1, inactivityWarning = null) }
    }
    fun reportInactivity(message: String?) {
        if (state.value.inactivityWarning != message) mutableState.update { it.copy(inactivityWarning = message) }
    }
    override fun refreshCourseProgress() {
        if (courseProgress == null) return
        mutableState.update { state ->
            val activity = state.activity as? PageActivity.Automation ?: return@update state
            state.copy(activity = activity.copy(detail = "Local progress is saved automatically as videos and assessments finish."))
        }
    }
    override fun loadProgressHistory(account: String) {
        val profile = state.value.progressAccounts.singleOrNull { it.key == account } ?: return
        historySelection = account
        scope.launch {
            try {
                val rows = progressRepository.records(account).filter { it.kind != "profile-use" }
                if (historySelection == account) mutableState.update { it.copy(savedProgress = CourseProgressSummary(account,profile.displayName,profile.checkedAt,rows), savedProgressProfiles = emptyList(), progressHistoryError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(progressHistoryError = "Could not load saved progress.") } }
        }
    }
    override fun loadAllProgressHistory() {
        scope.launch {
            try {
                val accounts = progressRepository.accounts().first()
                    .filter { it.key.startsWith("sagarmeinyog:") }
                val summaries = accounts.map { profile ->
                    CourseProgressSummary(profile.key, profile.displayName, profile.checkedAt,
                        progressRepository.records(profile.key).filter { it.kind != "profile-use" })
                }
                mutableState.update { it.copy(savedProgressProfiles = summaries, progressHistoryError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(progressHistoryError = "Could not load saved profiles.") } }
        }
    }
    override fun confirmProgressProfile(token: String, localName: String) {
        val tracking = courseProgress ?: return
        scope.launch {
            courseProgressMutex.withLock {
                if (courseProgress !== tracking) return@withLock
                val accepted = tracking.confirm(token, localName, now())
                mutableState.update { state ->
                    val activity = state.activity as? PageActivity.Automation ?: return@update state
                    state.copy(activity = activity.copy(progressProfile = tracking.request,
                        progressSummary = tracking.summary, detail = tracking.detail))
                }
                publish()
                if (accepted) automation.apps.bringToFront(BROWSER_APP_NAME)
            }
        }
    }
    override fun confirmManualAnswer(token: String, save: Boolean) {
        val runner = courseAutomation ?: return
        val bankStore = activeAnswerBankStore
        val request = runner.manualAnswer?.takeIf { it.token == token } ?: return
        val activity = state.value.activity as? PageActivity.Automation ?: return
        if (activity.savingAnswer) return
        mutableState.update { it.copy(activity = activity.copy(savingAnswer = save, answerSaveError = null)) }
        scope.launch {
            val result = if (save) try {
                bankStore.saveReviewed(request.module, listOf(ReviewedAnswer(CourseAnswer(request.question, request.options, request.answer), request.previousAnswer)))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AnswerBankSaveResult.Unavailable }
            else null
            if (courseAutomation !== runner || runner.manualAnswer?.token != token) return@launch
            val problem = when (result) {
                AnswerBankSaveResult.Conflict -> "The bank already has a different answer or changed while saving. Review it before replacing anything."
                AnswerBankSaveResult.Invalid -> "The answer bank needs review before this answer can be saved."
                AnswerBankSaveResult.Unavailable -> "Couldn't save the answer. Check that the answer-bank file is writable, or continue without saving."
                else -> null
            }
            if (problem == null) runner.acceptManualAnswer(token, (result as? AnswerBankSaveResult.Saved)?.bank)
            mutableState.update { state ->
                val current = state.activity as? PageActivity.Automation ?: return@update state
                state.copy(activity = current.copy(manualAnswer = runner.manualAnswer, savingAnswer = false, answerSaveError = problem))
            }
            if (problem == null) automation.apps.bringToFront(BROWSER_APP_NAME)
        }
    }

    override fun saveReviewedAnswers(token: String, answers: Map<String, String>) {
        val runner = courseAutomation ?: return
        val bankStore = activeAnswerBankStore
        val failure = runner.failure?.takeIf { it.token == token } ?: return
        val activity = state.value.activity as? PageActivity.Automation ?: return
        if (activity.savingAnswer || answers.isEmpty()) return
        val reviewed = answers.map { (id, answer) ->
            val question = failure.questions.singleOrNull { it.id == id } ?: return
            if (question.options.count { it == answer } != 1) return
            ReviewedAnswer(CourseAnswer(question.question, question.options, answer), question.bankAnswer)
        }
        mutableState.update { it.copy(activity = activity.copy(savingAnswer = true, answerSaveError = null)) }
        scope.launch {
            val result = try { bankStore.saveReviewed(failure.module, reviewed) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AnswerBankSaveResult.Unavailable }
            if (courseAutomation !== runner || runner.failure?.token != token) return@launch
            if (result is AnswerBankSaveResult.Saved) runner.configureAnswers(result.bank)
            mutableState.update { state ->
                val current = state.activity as? PageActivity.Automation ?: return@update state
                state.copy(activity = current.copy(savingAnswer = false,
                    answerSaveError = if (result is AnswerBankSaveResult.Saved) "Reviewed corrections saved."
                        else "Corrections were not saved. The bank may have changed or be unavailable; review and try again."))
            }
        }
    }

    override fun retryAssessment(token: String, automatic: Boolean) {
        val runner = courseAutomation ?: return
        if (runner.failure?.token != token) return
        if (automatic) {
            if (!runner.retryAssessment(token)) return
            mutableState.update { state ->
                val activity = state.activity as? PageActivity.Automation ?: return@update state
                state.copy(activity = activity.copy(failure = null, detail = "Starting one approved retry using this routine's assessment mode."))
            }
        } else stop()
        automation.apps.bringToFront(BROWSER_APP_NAME)
    }
    override fun approveAssessment(token: String) {
        val runner = courseAutomation ?: return
        if (runner.approval?.token != token) return
        runner.approveAssessment(token)
        mutableState.update { state ->
            val activity = state.activity as? PageActivity.Automation ?: return@update state
            state.copy(activity = activity.copy(approval = runner.approval))
        }
        automation.apps.bringToFront(BROWSER_APP_NAME)
    }

    override fun runCourseModule(module: String, action: CourseModuleAction) {
        val runner = courseAutomation ?: return
        runner.runModule(module, action)
        mutableState.update { state ->
            val activity = state.activity as? PageActivity.Automation ?: return@update state
            state.copy(activity = activity.copy(detail = runner.detail, moduleAction = action))
        }
        publish()
        automation.apps.bringToFront(BROWSER_APP_NAME)
    }

    override fun stopCourseModule() {
        val runner = courseAutomation ?: return
        runner.stopFocusedModule()
        mutableState.update { state ->
            val activity = state.activity as? PageActivity.Automation ?: return@update state
            state.copy(activity = activity.copy(detail = runner.detail, moduleAction = null))
        }
        publish()
    }
    private var questionAlertKey: String? = null
    private var questionAlertAt: Long? = null
    private val alertedAssessmentFailures = mutableSetOf<String>()
    private val assessmentAlertMutex = Mutex()
    private val courseProgressMutex = Mutex()
    private var failedAudioAttention: String? = null
    private var lastCourseInventoryAt = 0L
    private val pageReading = MutableStateFlow<PageReading?>(null)
    private val currentPreferences: StateFlow<AppPreferences> = preferences.stateIn(scope, SharingStarted.Eagerly, AppPreferences())
    private val mutableState = MutableStateFlow(PageState(isSupported = automation.access.access() != InputAccess.Unsupported, extension = extensionStatus.value))
    private val mutableLastRun = MutableStateFlow<RunRecord?>(null)
    private val scrollClickRun = ScrollClickRun(automation.pointer, automation.access) {
        if (!state.value.isConnected) RunOutcome.BrowserDisconnected else null
    }
    private var job: Job? = null
    private var lastPublished: String? = null
    private var textRequest = 0L
    private var pendingText: String? = null
    private var courseAutomation: CourseAutomationSession? = null
    private var activeAnswerBankStore: CourseAnswerBankStore = answerBankStore
    private var courseProgress: CourseProgressSession? = null
    private val courseChunks = CourseSnapshotAssembler()
    private var courseRunSequence = 0L
    private var lastCourseSnapshotSummary: String? = null
    private val textClicks = object : TextClicker {
        val done = MutableStateFlow(0L)
        override val clicked: StateFlow<Long> = done
        override fun request(text: String): Long {
            textRequest++
            pendingText = text
            live.log(LiveSource.Routine) { "Looking for the button \"$text\"" }
            publish()
            return textRequest
        }
        override fun clear() {
            if (pendingText == null) return
            pendingText = null
            publish()
        }
    }

    override val state: StateFlow<PageState> = mutableState.asStateFlow()
    override val lastRun: StateFlow<RunRecord?> = mutableLastRun.asStateFlow()

    init {
        publish()
        scope.launch {
            extensionStatus.collect { status -> mutableState.update { it.copy(extension = status.copy(connectedVersion = it.extension.connectedVersion)) } }
        }
        scope.launch {
            bridge.connected.collect { connected ->
                live.log(LiveSource.Chrome) { if (connected) "Extension connected" else "Extension not connected" }
                // Nothing is highlighted, as far as anyone knows, without a connection.
                mutableState.update { it.copy(isConnected = connected, foundLabel = it.foundLabel.takeIf { connected }, extension = if (connected) it.extension else it.extension.copy(connectedVersion = null)) }
            }
        }
        scope.launch {
            bridge.problem.collect { problem ->
                if (problem != null) live.log(LiveSource.Chrome) { "Link problem: $problem" }
                mutableState.update { it.copy(problem = problem) }
            }
        }
        scope.launch { progressRepository.accounts().collect { accounts -> mutableState.update { it.copy(progressAccounts = accounts) } } }
        scope.launch { bridge.incoming.collect(::onMessage) }
        scope.launch {
            automation.hotKeys.presses.collect { key ->
                val prefs = currentPreferences.value
                when (key) {
                    prefs.pageScrollHotKey -> toggleScrolling()
                    else -> Unit
                }
            }
        }
    }

    override fun selectDgsCourse(id: String) {
        val runner=dgsSession ?: return
        runner.selectCourse(id,dgsElapsedMillis())
        val activity=state.value.activity as? PageActivity.Dgs ?: return
        mutableState.update { it.copy(activity=activity.copy(progress=runner.progress)) }; publish()
        automation.apps.bringToFront(BROWSER_APP_NAME)
    }

    private var dgsStudy:DgsStudyCoordinator? = null
    private var dgsSession: DgsSession? = null
    private var dgsLastReading = 0L

    override fun start(routine: Routine): String? {
        if (isActive) return null
        progressMarkers.clear()
        observeProgress("run", newId())
        val refusal = startOrRefuse(routine)
        live.log(LiveSource.Routine) {
            if (refusal == null) "Started ${routine.name} (${routine.kind.label})" else "Didn't start ${routine.name}: $refusal"
        }
        return refusal
    }

    private fun startOrRefuse(routine: Routine): String? {
        state.value.problem?.let { return it }
        if (!state.value.isConnected) return NOT_CONNECTED
        state.value.extension.warning?.let { return it }
        return when (routine) {
            is DgsELearningRoutine -> {
                val runner = DgsSession("${newId()}:${now()}",routine,nativeClicks=automation.access.access()==InputAccess.Granted)
                dgsSession=runner; dgsStudy=DgsStudyCoordinator(dgsStudyRepository,runner,routine,now()); dgsLastReading=dgsElapsedMillis()
                launchActivity(PageActivity.Dgs(routine,runner.progress),scrolling=false) { performDgs(routine,runner) }
                automation.apps.bringToFront(BROWSER_APP_NAME)
                null
            }
            is CourseAutomationRoutine -> {
                val bankFile = com.jagdeep.shared.answers.resolvedAnswerBankFileName(routine.answerBankFile) ?: routine.answerBankFile
                activeAnswerBankStore = answerBankStore.forName(bankFile)
                lastCourseInventoryAt = now()
                courseAutomation = CourseAutomationSession("${newId()}:${now()}:${++courseRunSequence}", routine.waitAfterClickMillis, routine.playbackSpeed, routine.answerAssessments, routine.assessmentMode, routine.learningOrder, bankFile, routine.answerQuizzes)
                courseProgress = if (progressRepository.available) CourseProgressSession(progressRepository, courseAutomation!!.session, routine.waitAfterClickMillis) else null
                courseChunks.clear()
                launchActivity(
                    PageActivity.Automation(
                        routine.copy(answerBankFile = bankFile),
                        courseProgress?.detail ?: courseAutomation!!.detail,
                        progressProfile = courseProgress?.request,
                        // Show the complete course catalogue immediately. The live
                        // snapshot then replaces these rows with saved statuses.
                        modules = moduleStatuses(null),
                    ),
                    scrolling = false,
                ) { performCourseAutomation(routine) }
                if (courseProgress == null) automation.apps.bringToFront(BROWSER_APP_NAME)
                null
            }
            is ScrollClickRoutine -> {
                if (automation.access.access() != InputAccess.Granted) return "Allow Accessibility access so clicks can reach Chrome."
                pageReading.value = null
                val progress = ScrollClickProgress(1, routine.rounds, 0, ScrollClickPhase.ReadingPage)
                launchActivity(PageActivity.ScrollClick(routine, progress), scrolling = true) { performScrollClick(routine) }
                null
            }
            is ClickRoutine -> "A click routine doesn't run in Chrome."
        }
    }

    suspend fun awaitStopped() {
        job?.join()
        // Finish an already accepted progress write before the app closes its database.
        courseProgressMutex.withLock { }
    }

    override fun stop(): Boolean {
        val active = isActive
        if(dgsSession!=null) { dgsSession=null; publish() }
        if (state.value.activity is PageActivity.Automation) {
            courseAutomation = null
            courseProgress = null
            courseChunks.clear()
            publish()
        }
        if (active) job?.cancel()
        return active
    }

    /** During a scroll and click routine, this is its stop control. */
    override fun toggleScrolling() {
        when (state.value.activity) {
            is PageActivity.ScrollClick -> {
                live.log(LiveSource.Keys) { "Scroll key: stopping scroll and click" }
                stop()
            }
            is PageActivity.Automation, is PageActivity.Dgs, null -> Unit
        }
    }

    private fun launchActivity(activity: PageActivity, scrolling: Boolean, body: suspend () -> Unit) {
        // Set before launching, so a second press can never start a second routine.
        mutableState.update { it.copy(activity = activity, isScrolling = scrolling) }
        publish()
        job = scope.launch { body() }.also { started ->
            // Cancelled before its first dispatch, the body never runs to reset this.
            started.invokeOnCompletion { if (state.value.activity != null) endActivity() }
        }
    }

    private suspend fun performScrollClick(routine: ScrollClickRoutine) {
        val startedAt = now()
        var clicks = 0L
        var outcome = RunOutcome.StoppedByUser
        val prefs = currentPreferences.value
        try {
            // Start is pressed in this app, and nothing is clicked until Chrome is in front.
            live.log(LiveSource.App) { "Bringing $BROWSER_APP_NAME to the front" }
            automation.apps.bringToFront(BROWSER_APP_NAME)
            outcome = scrollClickRun.execute(
                steps = routine.steps,
                rounds = routine.rounds,
                safety = ScrollClickSafety(prefs.stopWhenPointerMoves, prefs.stopAtScreenCorner),
                pages = pageReading,
                setScrolling = ::setRunScrolling,
                texts = textClicks,
                onProgress = { progress ->
                    val previous = (state.value.activity as? PageActivity.ScrollClick)?.progress
                    if (progress.clicks > clicks) {
                        live.log(LiveSource.Click) { "Clicked step ${progress.step} of ${progress.steps} (round ${progress.round} of ${progress.rounds})" }
                    }
                    if (previous == null || previous.phase != progress.phase || previous.round != progress.round) {
                        live.log(LiveSource.Routine) { "Round ${progress.round} of ${progress.rounds}: ${progress.phase.name}" }
                    }
                    clicks = progress.clicks
                    mutableState.update { it.copy(activity = PageActivity.ScrollClick(routine, progress)) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Scroll and click failed: ${e.message}", TAG)
            outcome = RunOutcome.Failed
        } finally {
            withContext(NonCancellable) {
                endActivity()
                record(routine.name, startedAt, clicks, outcome)
            }
        }
    }

    private suspend fun performDgs(routine: DgsELearningRoutine, runner: DgsSession) {
        val started=now()
        val study=dgsStudy
        var outcome=RunOutcome.StoppedByUser
        try {
            while(true) {
                if(!state.value.isConnected) { outcome=RunOutcome.BrowserDisconnected; break }
                if(runner.completed) { outcome=RunOutcome.Completed; break }
                if(dgsElapsedMillis()-dgsLastReading>4_000) {
                    runner.stale()
                    mutableState.update { it.copy(activity=PageActivity.Dgs(routine,runner.progress)) }; publish()
                }
                study?.checkpoint(now(),elapsed=dgsElapsedMillis())
                val position=automation.pointer.position()
                if(currentPreferences.value.stopAtScreenCorner && position!=null && automation.pointer.isScreenCorner(position)) { outcome=RunOutcome.ScreenCorner; break }
                kotlinx.coroutines.delay(250)
            }
        } finally { withContext(NonCancellable) { study?.checkpoint(now(),if(runner.completed) "completed" else "stopped",dgsElapsedMillis()); endActivity(); record(routine.name,started,runner.progress.clicks,outcome) } }
    }

    private suspend fun performCourseAutomation(routine: CourseAutomationRoutine) {
        val startedAt = now()
        val runner = courseAutomation
        var outcome = RunOutcome.StoppedByUser
        try {
            courseProgress?.let { tracking ->
                courseProgressMutex.withLock {
                    tracking.prepareProfiles()
                    mutableState.update { state ->
                        val activity = state.activity as? PageActivity.Automation ?: return@update state
                        state.copy(activity = activity.copy(progressProfile = tracking.request, detail = tracking.detail))
                    }
                    publish()
                }
            }
            if (routine.answerAssessments) {
                val bank = try { activeAnswerBankStore.load() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                runner?.configureAnswers(bank)
            }
            if (courseProgress?.request == null) automation.apps.bringToFront(BROWSER_APP_NAME)
            while (true) {
                if (!state.value.isConnected) { outcome = RunOutcome.BrowserDisconnected; break }
                runner?.certificateDownload?.let { target ->
                    if (certificateStore.collectDownloaded(target)) {
                        runner.certificateDownloaded(target)
                        live.log(LiveSource.Routine) { "Saved ${target.module} certificate for ${target.profileName}" }
                    }
                }
                if (runner != null) updateAssessmentAlerts(runner)
                if (runner != null && now() - lastCourseInventoryAt > 5_000 && runner.approval == null && runner.attention == null && runner.failure == null && runner.manualAnswer == null && courseProgress?.request == null) {
                    mutableState.update { state ->
                        val activity = state.activity as? PageActivity.Automation ?: return@update state
                        val progressStep = courseProgress?.takeIf { it.isChecking }?.detail
                        val detail = if (progressStep != null) "Waiting for page updates. Keep the course tab active in Chrome. Last progress step: $progressStep"
                            else "Waiting for page updates. Keep the course tab active in Chrome."
                        state.copy(activity = activity.copy(detail = detail))
                    }
                }
                val prefs = currentPreferences.value
                val position = automation.pointer.position()
                if (prefs.stopAtScreenCorner && position != null && automation.pointer.isScreenCorner(position)) {
                    outcome = RunOutcome.ScreenCorner; break
                }
                val finished = courseProgressMutex.withLock { runner?.completed == true && courseProgress?.isChecking != true }
                if (finished) { outcome = RunOutcome.Completed; break }
                kotlinx.coroutines.delay(100)
            }
        } finally {
            withContext(NonCancellable) {
                val clicks = runner?.clicks ?: 0
                endActivity()
                record(routine.name, startedAt, clicks, outcome)
            }
        }
    }

    private suspend fun record(name: String, startedAt: Long, clicks: Long, outcome: RunOutcome) {
        val record = RunRecord(newId(), name, startedAt, now(), clicks, outcome)
        live.log(LiveSource.Routine) { "Ended $name: ${outcome.label}, $clicks clicks" }
        mutableLastRun.value = record
        history.add(record)
    }

    private fun setRunScrolling(scrolling: Boolean) {
        if (state.value.isScrolling == scrolling) return
        mutableState.update { it.copy(isScrolling = scrolling) }
        publish()
    }

    private fun endActivity() {
        dgsSession=null
        dgsStudy=null
        courseAutomation = null
        courseProgress = null
        courseChunks.clear()
        questionAlertKey = null
        questionAlertAt = null
        alertedAssessmentFailures.clear()
        failedAudioAttention = null
        mutableState.update { it.copy(activity = null, isScrolling = false) }
        publish()
    }

    private suspend fun onMessage(raw: String) {
        if(raw.length<=4_000) {
            val event=runCatching {json.decodeFromString(DgsBrowserAction.serializer(),raw)}.getOrNull()
            if(event?.type=="dgsBrowserAction") {
                val runner=dgsSession ?: return
                runner.acknowledgeBrowserAction(event)
                val activity=state.value.activity as? PageActivity.Dgs ?: return
                mutableState.update {it.copy(activity=activity.copy(progress=runner.progress))};publish();return
            }
        }

        if(raw.length<=4_000) {
            val event=runCatching { json.decodeFromString(DgsClickEvent.serializer(),raw) }.getOrNull()
            if(event?.type=="dgsClick") {
                val runner=dgsSession ?: return
                if(event.x !in -100_000..100_000 || event.y !in -100_000..100_000 || !runner.claimNativeClick(event)) return
                val point=ScreenPoint(event.x,event.y)
                val safe=state.value.isConnected && automation.access.access()==InputAccess.Granted && automation.apps.frontmost()==BROWSER_APP_NAME && !automation.pointer.isOverOwnFocusedWindow(point)
                if(!safe || !automation.pointer.click(point,com.jagdeep.automation.PointerButton.Left,1)) runner.nativeClickFailed("Couldn't click DGS. Keep its Chrome window in front, check Accessibility access and select the course again, or use the page button manually.")
                else {runner.nativeClickSucceeded(); live.log(LiveSource.Click) { "DGS native click: ${event.action}" }}
                val activity=state.value.activity as? PageActivity.Dgs ?: return
                mutableState.update { it.copy(activity=activity.copy(progress=runner.progress)) }; publish(); return
            }
        }

        if(raw.length<=16_000) {
            val reading=runCatching { json.decodeFromString(DgsReading.serializer(),raw) }.getOrNull()
            if(reading?.type=="dgsReading") {
                val runner=dgsSession ?: return
                if(reading.session!=runner.session) return
                observeProgress("dgs", listOf(reading.page, reading.courseId, reading.scroll.toInt(), reading.atBottom,
                    reading.videos.map { listOf(it.id, it.position.toLong(), it.ended) }).toString())
                dgsLastReading=dgsElapsedMillis(); dgsStudy?.read(reading,now(),dgsElapsedMillis())
                val activity=state.value.activity as? PageActivity.Dgs ?: return
                if(activity.progress.detail!=runner.progress.detail) live.log(LiveSource.Chrome) { "DGS · ${runner.progress.page}: ${runner.progress.detail}" }
                mutableState.update { it.copy(activity=activity.copy(progress=runner.progress)) }; publish(); return
            }
        }
        if (handleCourseMessage(raw)) return
        live.log(LiveSource.Chrome) { "← ${raw.take(MAX_LOGGED_MESSAGE)}" }
        val event = try {
            json.decodeFromString(HelperEvent.serializer(), raw)
        } catch (_: SerializationException) {
            live.log(LiveSource.Chrome) { "Ignored a message that isn't valid" }
            return
        } catch (_: IllegalArgumentException) {
            live.log(LiveSource.Chrome) { "Ignored a message that isn't valid" }
            return
        }
        when (event.type) {
            "extensionVersion" -> {
                val version = event.version?.takeIf { Regex("[0-9]{1,5}(?:\\.[0-9]{1,5}){0,3}").matches(it) }
                mutableState.update { it.copy(extension = it.extension.copy(connectedVersion = version)) }
            }
            "found" -> mutableState.update { it.copy(foundLabel = event.label?.take(MAX_LABEL_LENGTH)) }
            "textClicked" -> if (event.request == textRequest) textClicks.done.value = textRequest
            "page" -> {
                observeProgress("scroll", "${event.signature}:${event.progress?.take(4000)}:${event.atBottom}:${event.video}")
                pageReading.value = PageReading(event.focused, event.atBottom, event.signature?.take(MAX_SIGNATURE_LENGTH), pageVideo(event.video))
            }
        }
    }

    private fun pageVideo(reported: String?): PageVideo = when (reported) {
        "playing" -> PageVideo.Playing
        "needsPlay" -> PageVideo.NeedsPlay
        else -> PageVideo.None
    }

    private fun publish() {
        val current = state.value
        val activity = current.activity
        val command = HelperCommand(
            enabled = activity != null,
            scrolling = current.isScrolling,
            pixelsPerSecond = when (activity) {
                is PageActivity.ScrollClick -> activity.routine.pixelsPerSecond
                null -> DEFAULT_PIXELS_PER_SECOND
                is PageActivity.Automation, is PageActivity.Dgs -> DEFAULT_PIXELS_PER_SECOND
            },
            words = emptyList(),
            advance = 0,
            autoRun = activity is PageActivity.ScrollClick,
            watchVideos = (activity as? PageActivity.ScrollClick)?.routine?.watchVideos ?: false,
            clickText = pendingText.takeIf { activity is PageActivity.ScrollClick },
            clickRequest = textRequest,
            course = false,
            dgs = dgsSession?.command,
            courseAutomation = courseAutomation?.let { CourseCommand(it.session, if (courseProgress?.blocksActions == true) null else courseProgress?.action ?: it.action, it.playbackSpeed) },
        )
        val encoded = json.encodeToString(HelperCommand.serializer(), command)
        if (encoded != lastPublished) live.log(LiveSource.Chrome) { "→ $encoded" }
        lastPublished = encoded
        bridge.publish(encoded)
    }

    private suspend fun handleCourseMessage(raw: String): Boolean {
        val event = runCatching { json.decodeFromString(CourseWireEvent.serializer(), raw) }.getOrNull() ?: return false
        if (event.type != "courseChunk" && event.type != "courseAction") return false
        val runner = courseAutomation ?: return true
        if (event.session != runner.session) return true
        courseProgressMutex.withLock {
        if (event.type == "courseAction") {
            live.log(LiveSource.Chrome) { "Course action ${event.request}: ${if (event.success) "accepted" else "refused"}" }
            if (courseProgress?.acknowledge(event.request, event.success, now()) != true) runner.acknowledge(event.request, event.success, now())
        } else courseChunks.accept(event)?.let { snapshot ->
            lastCourseSnapshot = snapshot
            lastCourseInventoryAt = now()
            observeProgress("course:${snapshot.document}", snapshot.progressMarker())
            val summary = "Course page ${snapshot.path} top=${snapshot.top} · " + snapshot.elements
                .filter { it.visible && (it.tag in setOf("h1", "h2", "h3", "h4") || it.expanded != null) }
                .joinToString(" | ") { "${it.tag}:${it.label.take(100)} expanded=${it.expanded}" }.take(1200) +
                (snapshot.assessment?.let { assessment -> " · Assessment ${assessment.module}: " + assessment.questions.joinToString { "${it.id} ${it.text.take(140)}" } + " total=${assessment.totalQuestions} start=${assessment.start != null} result=${assessment.result}" } ?: "")
            if (summary != lastCourseSnapshotSummary) {
                lastCourseSnapshotSummary = summary
                live.log(LiveSource.Chrome) { summary }
            }
            if (snapshot.top) mutableState.update { state ->
                val current = state.activity as? PageActivity.Automation ?: return@update state
                val modulePath = snapshot.path.split('/').take(4).joinToString("/")
                val module = snapshot.assessment?.module?.takeIf { it.isNotBlank() }
                    ?: snapshot.elements.firstOrNull { Regex("/dashboard/courses/[A-Za-z0-9_-]+").matches(modulePath) && it.href == modulePath && it.label.isNotBlank() }?.label
                    ?: current.context.module.takeIf { snapshot.path.startsWith("/dashboard/courses/") }
                val lesson = snapshot.elements.firstOrNull { it.tag in setOf("h1","h2","h3","h4") && Regex("^Part\\s*:",RegexOption.IGNORE_CASE).containsMatchIn(it.label) }?.label
                val question = snapshot.assessment?.questions?.firstOrNull { q -> q.options.none { it.selected } }?.text
                state.copy(activity = current.copy(context = CourseRunContext(module,lesson,question)))
            }
            val previousAction = runner.action
            val tracking = courseProgress
            val progressHandled = tracking?.process(snapshot, now(), learningActionPending = runner.action != null) == true
            if (courseAutomation !== runner) return true
            if (!progressHandled) {
                runner.configureCertificates(courseProgress?.summary?.displayName, certificateStore)
                runner.configureProgress(tracking?.summary)
                runner.read(snapshot, now())
                tracking?.capture(snapshot, runner, now())
                runner.configureProgress(tracking?.summary)
            }
            updateAssessmentAlerts(runner)
            if (runner.action != null && runner.action != previousAction) {
                if (runner.action?.kind == "certificateDownload") {
                    runner.certificateDownload?.let(certificateStore::prepareDownload)
                }
                val target = snapshot.elements.firstOrNull { it.id == runner.action?.element }
                live.log(LiveSource.Chrome) {
                    "Course target ${target?.id}: ${target?.tag} '${target?.label}' · ${target?.context?.take(180)}"
                }
            }
        }
        val activity = state.value.activity as? PageActivity.Automation ?: return true
        val key = runner.attention ?: runner.failure?.token
        val detail = (courseProgress?.takeIf { it.action != null || it.request != null || it.isChecking }?.detail ?: runner.detail) + if (key != null && key == failedAudioAttention) " Sound unavailable; check the audio output." else ""
        mutableState.update { it.copy(activity = activity.copy(detail = detail, clicks = runner.clicks, approval = runner.approval,
            context = activity.context.copy(module = runner.currentModule ?: activity.context.module),
            manualAnswer = runner.manualAnswer, failure = runner.failure,
            progressProfile = courseProgress?.request, progressSummary = courseProgress?.summary,
            modules = moduleStatuses(courseProgress?.summary), moduleAction = runner.moduleAction,
            savingAnswer = activity.savingAnswer && activity.manualAnswer?.token == runner.manualAnswer?.token,
            answerSaveError = activity.answerSaveError.takeIf { activity.manualAnswer?.token == runner.manualAnswer?.token })) }
        publish()
        return true
        }
    }

    private fun moduleStatuses(summary: CourseProgressSummary?): List<CourseModuleStatus> {
        val profile = summary?.displayName ?: return SAGAR_MEIN_YOG_MODULES.map { CourseModuleStatus(it, false, false, false) }
        val records = summary.records
        return SAGAR_MEIN_YOG_MODULES.map { module ->
            val normalized = CourseAnswerBank.normalize(module)
            val videos = records.any { it.kind == "module-videos" && it.status == "completed" && CourseAnswerBank.normalize(it.course) == normalized }
            val passed = records.any { it.kind == "assessment" && it.status == "passed" && CourseAnswerBank.normalize(it.course) == normalized }
            val certificate = passed && certificateStore.hasCertificate(CourseCertificateTarget(profile, module))
            val assessment = records.filter { it.kind == "assessment" && CourseAnswerBank.normalize(it.course) == normalized }
                .maxByOrNull { it.observedAt }
            val details = assessment?.details.orEmpty()
            fun metric(name: String) = Regex("(?:^|\\n)$name=([0-9]+)").find(details)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val video = records.filter { it.kind == "module-videos" && CourseAnswerBank.normalize(it.course) == normalized }
                .maxByOrNull { it.observedAt }
            CourseModuleStatus(module, videos, passed, certificate, assessment?.score, metric("automaticAnswers"), metric("manualAnswers"),
                detailMetric(video?.details.orEmpty(), "durationMillis"), detailMetric(details, "durationMillis"))
        }
    }

    private fun updateAssessmentAlerts(runner: CourseAutomationSession) {
        scope.launch {
            assessmentAlertMutex.withLock {
                if (courseAutomation === runner) emitAssessmentAlert(runner)
            }
        }
    }

    private fun emitAssessmentAlert(runner: CourseAutomationSession) {
        if (!currentPreferences.value.assessmentSoundsEnabled) {
            questionAlertKey = null
            questionAlertAt = null
            return
        }
        val failed = runner.failure
        val key: String
        val kind: AssessmentAlertKind
        if (failed != null) {
            if (!alertedAssessmentFailures.add(failed.token)) return
            key = failed.token
            kind = AssessmentAlertKind.Failure
        } else {
            val question = runner.attention
            if (question == null) {
                questionAlertKey = null
                questionAlertAt = null
                return
            }
            if (question == questionAlertKey && questionAlertAt?.let { now() - it < 30_000 } == true) return
            key = question
            kind = AssessmentAlertKind.Question
            questionAlertKey = question
            questionAlertAt = now()
        }
        runCatching { assessmentAlerts.play(kind) }.onFailure {
            failedAudioAttention = key
            mutableState.update { state ->
                val activity = state.activity as? PageActivity.Automation ?: return@update state
                state.copy(activity = activity.copy(detail = runner.detail + " Sound unavailable; check the audio output."))
            }
        }
        live.log(LiveSource.App) { "Assessment alert ($kind): ${runner.detail}" }
    }

    private companion object {
        const val TAG = "PageController"
        const val MAX_LABEL_LENGTH = 60
        const val MAX_SIGNATURE_LENGTH = 128
        const val MAX_LOGGED_MESSAGE = 300
        const val NOT_CONNECTED = "Open Chrome with the extension added first. The steps are in Settings."
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
