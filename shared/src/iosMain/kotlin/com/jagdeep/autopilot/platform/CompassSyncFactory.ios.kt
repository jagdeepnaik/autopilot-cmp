package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.shared.compass.CompassSync
import com.jagdeep.shared.compass.NoCompassSync
import kotlinx.coroutines.CoroutineScope

// Course routines (and their Q&A files) are desktop only for now.
internal actual fun createCompassSync(answerBanks: CourseAnswerBankStore, scope: CoroutineScope, log: (String) -> Unit): CompassSync = NoCompassSync
