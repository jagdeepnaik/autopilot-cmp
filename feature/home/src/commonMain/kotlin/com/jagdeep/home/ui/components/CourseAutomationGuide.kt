package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Help is shared by the routine editor and its run panel; no setup actions happen here. */
@Composable
internal fun CourseAutomationGuide() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Setup and how it works") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Sagar Mein Yog automation guide") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GuideSection("You: prepare Chrome",
                    "1. In this app’s Settings, find Chrome extension and choose Show extension folder. In Chrome, open chrome://extensions, enable Developer mode, choose Load unpacked and select that folder. If already installed, reload AutoPilot Page Helper after an app update.",
                    "2. Open https://sagarmeinyog.com/dashboard in Chrome and sign in yourself. Wait until your courses are visible. The app does not sign in for you.",
                    "3. Select a Sagar Mein Yog automation routine. Check its assessment mode, playback speed and wait after each click. Start the routine, then leave the course tab selected with Chrome in front. Keep the app running.")
                GuideSection("Progress for each user",
                    "Before a run, confirm your local profile name in the app. Reuse it for the same website account; choose distinct profile names if users share a displayed name. No password is stored.",
                    "The app reads every page of Your Progress before continuing and after each completed module. Keep All Time selected and clear progress filters. Assessment attempts and scores are saved locally; video completion is checked on course pages. Earlier confirmed passes are preserved even when a newer attempt is unsubmitted.",
                    "Use Saved progress by user inside the course routine’s options to view history later. A changed signed-in name, failed progress import or local save error pauses the run.")
                GuideSection("App: follow the course",
                    "Reads the visible course controls and completion status, opens available unfinished video lessons, waits for playback to finish and looks for the next lesson. After a module’s videos finish, it handles the assessment according to your routine settings, then returns to the dashboard to continue.",
                    "Completed content is skipped when the site reports it complete. Locked, unavailable or unrecognized steps require your attention; the app cannot guarantee every course will finish.")
                GuideSection("You: choose assessment behavior",
                    "Assessments off: handle quizzes and assessments yourself.",
                    "Manual: approve starts and submissions, and choose each answer in Chrome.",
                    "Review approvals: the app selects saved answers; you approve each assessment start and submission.",
                    "Automatic: starting this routine authorizes assessment starts, saved-answer selection and submission. Starts can consume attempts. Unknown answers still pause; failed assessments still require a retry choice.")
                GuideSection("When your help is needed",
                    "Unknown answer: check the bottom status bar. With Assessment sounds enabled in Settings, a reminder plays every 30 seconds. Choose an answer in Chrome, return to the app, then approve Save and continue only after checking it. Continue without saving uses it for this attempt only.",
                    "Not cleared: a different sound plays. Choose Review and correct answers, select verified corrections, then approve the save popup to update the question bank for future use. A score alone does not tell the app which answers are wrong. Review is available for questions captured during this run.",
                    "Start automatic retry authorizes one new attempt using this routine’s mode. Retry manually stops automation so you can take over in Chrome.")
                GuideSection("If nothing happens",
                    "Keep the signed-in course tab selected and Chrome in front. Check Settings for the extension connection and read the bottom status bar. If playback is blocked, press Play in Chrome. Respond to any approval or answer popup in the app, then return to Chrome.",
                    "After reloading the extension, refresh the course page only when no timed assessment is active. Avoid switching tabs during a timed test; the site may penalize it.",
                    "Stop at any time with the Stop button or your configured Start and stop hot key. Stopping the app does not cancel a website assessment timer.")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Got it") } },
    )
}

@Composable
private fun GuideSection(title: String, vararg paragraphs: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
