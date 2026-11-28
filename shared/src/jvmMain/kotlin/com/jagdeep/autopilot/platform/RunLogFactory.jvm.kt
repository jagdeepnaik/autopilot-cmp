package com.jagdeep.autopilot.platform

import com.jagdeep.autopilot.data.FileRunLog
import com.jagdeep.autopilot.data.FileRunLogOutbox
import com.jagdeep.home.domain.cosmos.NoRunLogOutbox
import com.jagdeep.logger.Log
import com.jagdeep.shared.diagnostics.NoRunLog
import com.jagdeep.shared.diagnostics.NoRunLogFiles
import kotlinx.coroutines.flow.emptyFlow
import com.jagdeep.shared.diagnostics.RunLogFiles
import com.jagdeep.shared.diagnostics.RunLogSanitizer
import java.awt.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createRunLogServices(): RunLogServices {
    // Without a writable folder the app still runs; it just keeps no run logs.
    val folder = runCatching { desktopDataPaths.runLogs() }.getOrNull()
        ?: return RunLogServices(NoRunLog, NoRunLogFiles, NoRunLogOutbox, emptyFlow())
    // 30-day local retention, at start-up and off the start-up path.
    Thread({
        runCatching { FileRunLog.prune(folder, System.currentTimeMillis()) }
            .onSuccess { if (it > 0) Log.i("Removed $it old run-log folder(s)", "RunLog") }
    }, "run-log-retention").apply { isDaemon = true }.start()
    val home = System.getProperty("user.home").orEmpty()
    val user = System.getProperty("user.name").orEmpty()
    val writer = FileRunLog(folder, appBuildIdentity, FileRunLog.osDescription(), RunLogSanitizer(listOf(home, user)))
    val files = object : RunLogFiles {
        override val isAvailable = true
        override suspend fun openFolder(): String? = withContext(Dispatchers.IO) {
            folder.mkdirs()
            if (runCatching { Desktop.getDesktop().open(folder) }.isSuccess) null
            else "Couldn't open the logs folder. It's the logs/runs folder inside .autopilot in your home folder."
        }
    }
    return RunLogServices(writer, files, FileRunLogOutbox(folder) { writer.activeRunId }, writer.finished, writer::close)
}
