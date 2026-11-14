package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.InputAccess
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.HomeUiState
import com.jagdeep.home.domain.PageActivity
import com.jagdeep.home.domain.RunState
import com.jagdeep.home.domain.needsInputAccess
import com.jagdeep.home.ui.util.clicksLabel
import com.jagdeep.home.ui.util.lastRunLabel
import com.jagdeep.home.ui.util.pauseLabel
import com.jagdeep.home.ui.util.scrollClickDetail
import com.jagdeep.ui.themes.AppColors

/** The current status and the one next action: start the selected routine, or stop what's running. */
@Composable
internal fun RunPanel(
    state: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
    onApproveAssessment: (String) -> Unit = {},
    onProgressProfile: (String, String) -> Unit = { _, _ -> },
    onManualAnswer: (String, Boolean) -> Unit = { _, _ -> },
    onSaveReviewedAnswers: (String, Map<String, String>) -> Unit = { _, _ -> },
    onRetryAssessment: (String, Boolean) -> Unit = { _, _ -> },
    showStopControl: Boolean = true,
    showProgressSummary: Boolean = true,
    statusHeading: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    val page = state.page
    val activity = page.activity
    if (activity is PageActivity.Automation) {
        CourseProgressPanel(activity.progressProfile, activity.progressSummary.takeIf { showProgressSummary }, onProgressProfile)
    }
    if (activity is PageActivity.Automation && activity.manualAnswer != null) {
        val request = activity.manualAnswer
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Save this answer to the question bank?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.module, style = MaterialTheme.typography.labelLarge)
                    Text(request.question)
                    Text("Selected answer: ${request.answer}")
                    Text("Save only if you have checked this answer. The app has not independently verified it.", style = MaterialTheme.typography.bodySmall)
                    activity.answerSaveError?.let { Text(it, color = colors.error) }
                }
            },
            confirmButton = { TextButton(enabled = !activity.savingAnswer, onClick = { onManualAnswer(request.token, true) }) { Text(if (activity.savingAnswer) "Saving…" else "Save and continue") } },
            dismissButton = { TextButton(enabled = !activity.savingAnswer, onClick = { onManualAnswer(request.token, false) }) { Text("Continue without saving") } },
        )
    }
    if (activity is PageActivity.Automation && activity.failure != null) {
        AssessmentReview(activity.failure, activity.savingAnswer, activity.answerSaveError, onSaveReviewedAnswers)
    }
    // Website automation needs Chrome; pointer-based routines also require input permission.
    val needsAccess = state.selected?.needsInputAccess() != false
    val access = if (needsAccess) state.access else InputAccess.Granted
    // Why a selected Chrome routine can't reach Chrome, such as another program holding its port.
    val chromeProblem = page.problem.takeIf { state.selected != null && state.selected !is ClickRoutine }
    val (icon, tint) = statusAppearance(state, access)
    val title = when {
        activity is PageActivity.Dgs -> "DGS E-Learning on"
        activity is PageActivity.Automation -> "Sagar Mein Yog automation on"
        activity is PageActivity.ScrollClick -> "Round ${activity.progress.round} of ${activity.progress.rounds}"
        run is RunState.CountingDown -> "Starting in ${run.secondsLeft}…"
        run is RunState.Running -> if (run.pause != null) "Paused" else "Clicking"
        chromeProblem != null -> "Can't reach Chrome"
        access == InputAccess.Unsupported -> "Desktop only for now"
        access == InputAccess.NeedsPermission -> "Allow access to click"
        state.selected == null -> "No routine yet"
        else -> "Ready"
    }
    val detail = when {
        activity is PageActivity.ScrollClick -> "${activity.routine.name} · ${scrollClickDetail(activity.progress)}"
        activity is PageActivity.Dgs -> activity.progress.detail
        activity is PageActivity.Automation -> activity.detail
        run is RunState.CountingDown -> "Move to the target now. ${run.routine.name} starts soon."
        run is RunState.Running -> buildString {
            append(run.routine.name).append(" · ").append(clicksLabel(run.clicks))
            run.pause?.let { append(". ").append(pauseLabel(it)) }
        }
        chromeProblem != null -> chromeProblem
        access == InputAccess.Unsupported -> "Clicking in other apps works on Mac and Windows."
        access == InputAccess.NeedsPermission -> "macOS only lets an app click in other apps after you turn it on under Privacy & Security → Accessibility."
        state.selected == null -> "Create a routine to get started."
        else -> state.lastRun?.let(::lastRunLabel) ?: state.selected.name
    }
    val isActive = state.isActive
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = tint.copy(alpha = 0.14f), contentColor = tint) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(statusHeading ?: title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (activity is PageActivity.Automation && activity.failure != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("An automatic retry starts one new attempt and may consume an attempt. The retry uses this routine’s assessment mode.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = activity.failure.canRetry && !activity.savingAnswer, onClick = { onRetryAssessment(activity.failure.token, true) }) { Text("Start automatic retry") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !activity.savingAnswer, onClick = { onRetryAssessment(activity.failure.token, false) }) { Text("Retry manually") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (activity is PageActivity.Automation && activity.approval?.kind == com.jagdeep.home.domain.CourseApprovalKind.StartAssessmentPass) {
                AlertDialog(
                    onDismissRequest = onStop,
                    title = { Text("All videos finished") },
                    text = { Text("Begin unfinished module assessments now? Already-passed assessments will be skipped. Your selected assessment mode still controls starts, answers and submissions; unknown answers pause for help.") },
                    confirmButton = { TextButton(onClick = { onApproveAssessment(activity.approval.token) }) { Text("Begin assessments") } },
                    dismissButton = { TextButton(onClick = onStop) { Text("Not now — stop routine") } },
                )
            }
            if (activity is PageActivity.Automation && activity.approval?.kind == com.jagdeep.home.domain.CourseApprovalKind.SubmitAssessment) {
                AlertDialog(onDismissRequest = {}, title = { Text("Submit assessment?") },
                    text = { Text("All answers for ${activity.approval.module} are selected. Review them in Chrome before approving the final submission.") },
                    confirmButton = { TextButton(onClick = { onApproveAssessment(activity.approval.token) }) { Text("Approve submission") } },
                    dismissButton = { TextButton(onClick = onStop) { Text("Stop without submitting") } })
            }
            if (activity is PageActivity.Automation && activity.approval?.kind == com.jagdeep.home.domain.CourseApprovalKind.CertificateDownloaded) {
                AlertDialog(onDismissRequest = {}, title = { Text("Download certificate manually") },
                    text = { Text("${activity.approval.module} assessment passed. In Chrome, download the certificate manually now. When it is complete, confirm below so AutoPilot records the certificate and continues.") },
                    confirmButton = { TextButton(onClick = { onApproveAssessment(activity.approval.token) }) { Text("I downloaded the certificate") } },
                    dismissButton = { TextButton(onClick = onStop) { Text("Stop for now") } })
            }
            if (activity is PageActivity.Automation && activity.approval != null && activity.approval.kind !in setOf(
                    com.jagdeep.home.domain.CourseApprovalKind.SubmitAssessment,
                    com.jagdeep.home.domain.CourseApprovalKind.CertificateDownloaded,
                )) {
                Button(onClick = { onApproveAssessment(activity.approval.token) }) {
                    Text(when(activity.approval.kind) {
                        com.jagdeep.home.domain.CourseApprovalKind.StartAssessment -> "Allow assessment attempt"
                        com.jagdeep.home.domain.CourseApprovalKind.StartAssessmentPass -> "Begin assessment pass"
                        com.jagdeep.home.domain.CourseApprovalKind.SubmitAssessment -> "Approve submission"
                        com.jagdeep.home.domain.CourseApprovalKind.CertificateDownloaded -> "I downloaded the certificate"
                    })
                }
            }
            when {
                isActive -> if (showStopControl) Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Stop", Modifier.padding(start = 8.dp))
                }
                access == InputAccess.NeedsPermission -> Button(onClick = onRequestAccess) { Text("Allow access") }
                else -> Button(onClick = onStart, enabled = state.selected != null && access == InputAccess.Granted) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Start", Modifier.padding(start = 8.dp))
                }
            }
            if (access == InputAccess.Granted && showStopControl) {
                Text(
                    when {
                        isActive -> "Press ${state.toggleHotKey.name} in any app to stop"
                        else -> "Press ${state.toggleHotKey.name} in any app to start or stop"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusAppearance(state: HomeUiState, access: InputAccess): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    return when {
        (state.page.activity as? PageActivity.Dgs)?.progress?.needsAttention == true -> Icons.Default.PauseCircleOutline to colors.primary
        (state.page.activity as? PageActivity.Automation)?.failure != null -> Icons.Default.ErrorOutline to colors.error
        (state.page.activity as? PageActivity.Automation)?.let { it.approval!=null || it.manualAnswer!=null || it.progressProfile!=null } == true -> Icons.Default.PauseCircleOutline to colors.primary
        state.page.activity != null -> Icons.Default.TravelExplore to AppColors.live
        run is RunState.CountingDown -> Icons.Default.Timer to colors.primary
        run is RunState.Running && run.pause != null -> Icons.Default.PauseCircleOutline to colors.onSurfaceVariant
        run is RunState.Running -> Icons.Default.AdsClick to AppColors.live
        access != InputAccess.Granted -> Icons.Default.ErrorOutline to colors.error
        else -> Icons.Default.AdsClick to colors.primary
    }
}
