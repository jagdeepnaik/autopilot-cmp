package com.jagdeep.home.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val routines: List<Routine> = emptyList(),
    val selected: Routine? = null,
    val run: RunState = RunState.Idle,
    val page: PageState = PageState(),
    val lastRun: RunRecord? = null,
    val access: InputAccess = InputAccess.Granted,
    val toggleHotKey: HotKey = HotKey.F6,
    val routinePendingDeletion: Routine? = null,
    /** Whether routines can be exported to and imported from files here. */
    val canUseFiles: Boolean = true,
) {
    /** True while any routine runs, of any kind. */
    val isActive: Boolean get() = run != RunState.Idle || page.activity != null
}

/** Website automation uses the extension; pointer-based routines need native input permission. */
fun Routine.needsInputAccess(): Boolean = this !is CourseAutomationRoutine && this !is DgsELearningRoutine

class HomeViewModel(
    private val controller: ClickController,
    private val repository: RoutineRepository,
    private val files: RoutineFiles = NoRoutineFiles,
    private val newId: () -> String = { "" },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(access = controller.access(), canUseFiles = files.isAvailable))
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)

    /** Short results of an export or import, for a toast. */
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.library.collect { library ->
                mutableState.update {
                    it.copy(isLoading = library == null, routines = library?.routines.orEmpty(), selected = library?.selected)
                }
            }
        }
        viewModelScope.launch { controller.state.collect { run -> mutableState.update { it.copy(run = run) } } }
        viewModelScope.launch { controller.page.state.collect { page -> mutableState.update { it.copy(page = page) } } }
        viewModelScope.launch {
            // Whichever kind of routine finished most recently.
            combine(controller.lastRun, controller.page.lastRun) { click, page ->
                listOfNotNull(click, page).maxByOrNull { it.endedAtMillis }
            }.collect { last -> mutableState.update { it.copy(lastRun = last) } }
        }
        viewModelScope.launch {
            controller.preferences.collect { prefs ->
                mutableState.update {
                    it.copy(toggleHotKey = prefs.toggleHotKey)
                }
            }
        }
        watchAccess()
    }

    fun start() {
        state.value.selected?.let(controller::start)
        // Access can be turned off in System Settings at any time.
        mutableState.update { it.copy(access = controller.access()) }
        watchAccess()
    }

    fun stop() = controller.stop()

    fun selectDgsCourse(id: String) = controller.page.selectDgsCourse(id)

    fun approveAssessment(token: String) = controller.page.approveAssessment(token)
    fun confirmProgressProfile(token: String, localName: String) = controller.page.confirmProgressProfile(token, localName)
    fun confirmManualAnswer(token: String, save: Boolean) = controller.page.confirmManualAnswer(token, save)
    fun saveReviewedAnswers(token: String, answers: Map<String, String>) = controller.page.saveReviewedAnswers(token, answers)
    fun retryAssessment(token: String, automatic: Boolean) = controller.page.retryAssessment(token, automatic)

    fun select(routine: Routine) {
        viewModelScope.launch { repository.select(routine.id) }
    }

    fun requestAccess() {
        controller.requestAccess()
    }

    private val transfer = RoutineFileTransfer(repository, files, newId) { controller.isActive }
    fun exportAll() = exportIds(state.value.routines.map { it.id }.toSet())
    fun export(routine: Routine) = exportIds(setOf(routine.id))
    private fun exportIds(ids: Set<String>) { viewModelScope.launch { transfer.exportRoutines(ids)?.let { mutableMessages.send(it) } } }
    fun import() { viewModelScope.launch { transfer.importRoutines()?.let { mutableMessages.send(it) } } }

    fun requestDeletion(routine: Routine) = mutableState.update { it.copy(routinePendingDeletion = routine) }

    fun cancelDeletion() = mutableState.update { it.copy(routinePendingDeletion = null) }

    fun confirmDeletion() {
        val routine = state.value.routinePendingDeletion ?: return
        mutableState.update { it.copy(routinePendingDeletion = null) }
        viewModelScope.launch { repository.delete(routine.id) }
    }

    /** Notices the user allowing access in System Settings; only polls while it is missing. */
    private var watchingAccess = false

    private fun watchAccess() {
        if (watchingAccess || state.value.access != InputAccess.NeedsPermission) return
        watchingAccess = true
        viewModelScope.launch {
            while (state.value.access == InputAccess.NeedsPermission) {
                delay(ACCESS_CHECK_MILLIS)
                val access = controller.access()
                mutableState.update { it.copy(access = access) }
            }
            watchingAccess = false
        }
    }

    private companion object {
        const val ACCESS_CHECK_MILLIS = 2_000L
    }
}

/** The same routine under a new id. */
internal fun Routine.withId(id: String): Routine = when (this) {
    is ClickRoutine -> copy(id = id)
    is ScrollClickRoutine -> copy(id = id)
    is CourseAutomationRoutine -> copy(id = id)
    is DgsELearningRoutine -> copy(id = id)
}
