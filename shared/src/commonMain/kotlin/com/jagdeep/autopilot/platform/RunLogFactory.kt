package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.RunLogOutbox
import com.jagdeep.shared.diagnostics.RunLog
import com.jagdeep.shared.diagnostics.RunLogFiles
import kotlinx.coroutines.flow.Flow

/**
 * The always-on routine run logs (desktop): the writer the routines use, the folder Settings shows,
 * the queue Cosmos uploads from, and a signal for each run that ends.
 */
internal class RunLogServices(
    val log: RunLog,
    val files: RunLogFiles,
    val outbox: RunLogOutbox,
    val finished: Flow<String>,
    val close: () -> Unit = {},
)

internal expect fun createRunLogServices(): RunLogServices
