package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.NoRunLogOutbox
import com.jagdeep.shared.diagnostics.NoRunLog
import com.jagdeep.shared.diagnostics.NoRunLogFiles
import kotlinx.coroutines.flow.emptyFlow

// Run logs are written on desktop computers only for now.
internal actual fun createRunLogServices(): RunLogServices = RunLogServices(NoRunLog, NoRunLogFiles, NoRunLogOutbox, emptyFlow())
