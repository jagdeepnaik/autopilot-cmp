package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.NoCosmosSync
import kotlinx.coroutines.CoroutineScope

// Course routines (and their Q&A files) are desktop only for now.
internal actual fun createCosmosSync(answerBanks: CourseAnswerBankStore, scope: CoroutineScope, runLogs: RunLogServices, log: (String) -> Unit): CosmosSync = NoCosmosSync
