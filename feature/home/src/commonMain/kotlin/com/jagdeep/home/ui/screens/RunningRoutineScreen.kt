package com.jagdeep.home.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jagdeep.home.domain.*
import com.jagdeep.home.ui.components.RunPanel
import com.jagdeep.home.ui.util.routineSummary
import com.jagdeep.home.ui.util.progressCheckedLabel
import com.jagdeep.ui.components.*
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.home.ui.navigation.RouteRunningRoutine

@Composable
fun RunningRoutineScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBar = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast.show(it) } }
    SideEffect { topBar.value = TopAppBarState(null) { AppBarTop("Running routine", RouteRunningRoutine,onBack,{},{}) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max=900.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val page = state.page.activity
            val routine = page?.routine ?: when(val run=state.run) { is RunState.Running -> run.routine; is RunState.CountingDown -> run.routine; else -> null }
            if (state.isActive) {
                Text(routine?.name.orEmpty(), Modifier.padding(horizontal=16.dp), style=MaterialTheme.typography.headlineMedium)
                RunPanel(state, viewModel::start,viewModel::stop,viewModel::requestAccess,
                    onApproveAssessment=viewModel::approveAssessment,onManualAnswer=viewModel::confirmManualAnswer,
                    onProgressProfile=viewModel::confirmProgressProfile,
                    onRetryAssessment=viewModel::retryAssessment,onSaveReviewedAnswers=viewModel::saveReviewedAnswers)
                HorizontalDivider()
                Text("Run details",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.titleMedium)
                routine?.let { Detail("Routine",routineSummary(it)) }
                val clicks = when(page) { is PageActivity.Automation -> page.clicks; is PageActivity.ScrollClick -> page.progress.clicks; else -> (state.run as? RunState.Running)?.clicks ?: 0 }
                Detail("Clicks",clicks.toString())
                if(page != null) Detail("Chrome",if(state.page.isConnected) "Connected" else "Not connected")
                if(page is PageActivity.ScrollClick) Detail("Round","${page.progress.round} of ${page.progress.rounds}")
                if(page is PageActivity.Automation) {
                    page.context.module?.let { Detail("Module",it) }
                    page.context.lesson?.let { Detail("Lesson",it) }
                    (page.manualAnswer?.question ?: page.context.question)?.let { Detail("Question",it) }
                    Detail("Playback speed","${page.routine.playbackSpeed}×")
                    Detail("Wait after each click","${page.routine.waitAfterClickMillis/1000} seconds")
                    Detail("Assessments",if(!page.routine.answerAssessments) "Manual outside the routine" else when(page.routine.assessmentMode) {
                        AssessmentMode.Manual -> "Manual answers with start and submission approvals"
                        AssessmentMode.Review -> "Saved answers with start and submission approvals"
                        AssessmentMode.Automatic -> "Automatic; unknown answers pause for help"
                    })
                    page.progressSummary?.let { Detail("User",it.displayName); Detail("Progress checked",progressCheckedLabel(it.checkedAt)) }
                    Text("Keep the course tab active and Chrome in front. Return here when the app needs an answer or approval.",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
                }
                Text("Going back keeps the routine running.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Run finished",style=MaterialTheme.typography.headlineMedium)
                    state.lastRun?.let { record ->
                        Text(record.routineName,style=MaterialTheme.typography.titleLarge)
                        Text(record.outcome.label)
                        Text("${record.clicks} clicks")
                    } ?: Text("No routine is running.")
                    Button(onClick=onBack) { Text("Back to routines") }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    ListItem(headlineContent={ Text(label) },supportingContent={ Text(value) },colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent))
}
