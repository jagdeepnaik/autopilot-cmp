package com.jagdeep.home.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jagdeep.home.domain.*
import com.jagdeep.home.ui.components.DgsCourseDialog
import com.jagdeep.home.ui.components.RunPanel
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
    val page = state.page.activity
    if(page is PageActivity.Dgs && page.progress.courses.isNotEmpty()) {
        DgsCourseDialog(page.progress.courses,viewModel::selectDgsCourse,viewModel::stop)
    }
    val routine = page?.routine ?: when(val run=state.run) {
        is RunState.Running -> run.routine
        is RunState.CountingDown -> run.routine
        else -> null
    }
    Column(Modifier.widthIn(max=900.dp).fillMaxWidth().fillMaxHeight()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if (state.isActive) {
                Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(routine?.name.orEmpty(),style=MaterialTheme.typography.titleLarge)
                    if(page is PageActivity.Automation) Text("Current module: ${page.context.module ?: "Choosing a module"}",
                        style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics { heading() })
                }
                RunPanel(state,viewModel::start,viewModel::stop,viewModel::requestAccess,
                    onApproveAssessment=viewModel::approveAssessment,onManualAnswer=viewModel::confirmManualAnswer,
                    onProgressProfile=viewModel::confirmProgressProfile,onRetryAssessment=viewModel::retryAssessment,
                    onSaveReviewedAnswers=viewModel::saveReviewedAnswers,showStopControl=false,showProgressSummary=false,
                    statusHeading=if(page is PageActivity.Automation) {
                        if(page.approval!=null || page.failure!=null || page.manualAnswer!=null || page.progressProfile!=null) "Needs your attention" else "Live status"
                    } else if(page is PageActivity.Dgs && page.progress.needsAttention) "Needs your attention" else null)
                if(page is PageActivity.Automation && (page.context.lesson!=null || page.context.question!=null || page.manualAnswer!=null)) {
                    RunSection("Current activity")
                    page.context.lesson?.let { Detail("Lesson",it) }
                    (page.manualAnswer?.question ?: page.context.question)?.let { Detail("Question",it) }
                }
                if(page is PageActivity.Dgs) {
                    RunSection("DGS study progress")
                    page.progress.courseName?.let { Detail("Selected course",it) }
                    Detail("DGS profile",page.progress.accountName.ifBlank {"Checking DGS"})
                    Detail("Current page",page.progress.page)
                    Detail("Pages read",page.progress.pagesRead.toString())
                    Detail("Total study time","${page.progress.activeMillis/3_600_000} h ${(page.progress.activeMillis/60_000)%60} min ${page.progress.activeMillis/1000%60} sec")
                    Detail("This run","${page.progress.currentRunMillis/60_000} min ${page.progress.currentRunMillis/1000%60} sec")
                    Detail("Saving",if(page.progress.lastSavedAt!=null) "Checkpoint saved · every 5 seconds" else "Waiting for first checkpoint")
                    Detail("Required / previously credited","${page.progress.requiredHours} h / ${page.progress.baselineHours} h")
                    Detail("Videos completed","${page.progress.completedVideos} / ${page.progress.observedVideos} observed")
                    Detail("Remaining study estimate","${(((page.progress.requiredHours-page.progress.baselineHours)*3_600_000).toLong()-page.progress.activeMillis).coerceAtLeast(0)/60_000} min")
                    Detail("Actual video speed",page.progress.actualSpeed?.let { "$it×" } ?: "No active video")
                    Detail("Reading pass",if(page.progress.reviewing) "Reviewing while time remains" else "Reading course content")
                    Detail("Assessments","Manual · not automated")
                    Detail("Reserved answer bank",page.routine.answerBankFile)
                }
                RunSection("Run progress")
                val clicks=when(page) {
                    is PageActivity.Dgs -> page.progress.clicks
                    is PageActivity.Automation -> page.clicks
                    is PageActivity.ScrollClick -> page.progress.clicks
                    else -> (state.run as? RunState.Running)?.clicks ?: 0
                }
                Detail("Clicks",clicks.toString())
                if(page!=null) Detail("Chrome",if(state.page.isConnected) "Connected" else "Not connected")
                if(page is PageActivity.ScrollClick) Detail("Round","${page.progress.round} of ${page.progress.rounds}")
                if(page is PageActivity.Automation) page.progressSummary?.let { summary ->
                    Detail("Modules with a passed assessment",summary.records.filter { it.kind=="assessment" && it.status=="passed" }.map { CourseAnswerBank.normalize(it.course) }.distinct().size.toString())
                    RunSection("Saved progress")
                    Detail("User",summary.displayName)
                    Detail("Last progress check",progressCheckedLabel(summary.checkedAt))
                }
                RunSection("Run details")
                routine?.let { Detail("Routine type",it.kind.label) }
                if(page is PageActivity.Automation) {
                    Detail("Playback speed","${page.routine.playbackSpeed}×")
                    Detail("Wait after each click","${page.routine.waitAfterClickMillis/1000} seconds")
                    Detail("Learning order",if(page.routine.learningOrder==LearningOrder.VideosFirst) "All videos first; confirm before assessments" else "Assessment after each module")
                    Detail("Assessment mode",if(!page.routine.answerAssessments) "Manual outside the routine" else when(page.routine.assessmentMode) {
                        AssessmentMode.Manual -> "Manual answers; approve starts and submissions"
                        AssessmentMode.Review -> "Saved answers; approve starts and submissions"
                        AssessmentMode.Automatic -> "Automatic; unknown answers pause for help"
                    })
                    Detail("Answer bank",page.routine.answerBankFile)
                    Text("Keep the course tab active and Chrome in front. Return here when an answer or approval is needed.",
                        Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        if(state.isActive) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Button(onClick=viewModel::stop,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)) {
                    Icon(Icons.Default.Stop,null,Modifier.size(18.dp)); Text("Stop",Modifier.padding(start=8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("${state.toggleHotKey.name} also stops the routine",style=MaterialTheme.typography.bodyMedium)
                    Text("Back keeps it running.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RunSection(title: String) {
    HorizontalDivider(Modifier.padding(horizontal=16.dp))
    Text(title,Modifier.padding(horizontal=16.dp).semantics { heading() },style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
}

@Composable
private fun Detail(label: String,value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)) {
        if(maxWidth<420.dp) Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.bodyMedium)
        } else Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
            Text(label,Modifier.weight(0.4f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,Modifier.weight(0.6f),style=MaterialTheme.typography.bodyMedium)
        }
    }
}
