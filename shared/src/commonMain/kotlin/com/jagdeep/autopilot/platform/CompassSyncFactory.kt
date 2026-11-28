package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.shared.compass.CompassSync
import kotlinx.coroutines.CoroutineScope

/**
 * Compass Q&A sync and run-log upload, the app's only outside connection (opt-in; desktop only).
 * Started at once: it stays idle until the user signs in, or resumes a saved session.
 */
internal expect fun createCompassSync(answerBanks: CourseAnswerBankStore, scope: CoroutineScope, runLogs: RunLogServices, log: (String) -> Unit): CompassSync
