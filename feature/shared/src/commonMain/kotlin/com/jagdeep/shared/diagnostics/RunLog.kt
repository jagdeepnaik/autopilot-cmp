package com.jagdeep.shared.diagnostics

/** Where a run-log event came from (private/docs/shared/02_DIAGNOSTICS_AND_DOWNLOADS_API.md). */
enum class RunLogSource(val wire: String) {
    Routine("routine"),
    Page("page"),
    Video("video"),
    Extension("extension"),
    Bridge("bridge"),
    Answers("answers"),
    Click("click"),
    Keys("keys"),
    App("app"),
    Sync("sync"),
}

enum class RunLogLevel(val wire: String) { Debug("debug"), Info("info"), Warn("warn"), Error("error") }

/**
 * The always-on log of one routine run: a header, its events and how it ended, written as it happens
 * so a run that hangs or crashes still leaves its log. Only one run is open at a time (one routine
 * runs at a time); events while no run is open are dropped.
 *
 * Every value passes [RunLogSanitizer] before it is written: `data` holds only numbers, booleans,
 * nulls and short strings, and never page addresses, page or question text, typed values or personal
 * details. Writing never throws and never blocks a routine for long.
 */
interface RunLog {
    /** True while a run's log is open. */
    val isOpen: Boolean

    /** Opens a new run's log (closing any open one as `Interrupted`). */
    fun start(routineKind: String, routineName: String, settings: Map<String, Any?> = emptyMap())

    fun event(source: RunLogSource, event: String, data: Map<String, Any?> = emptyMap(), level: RunLogLevel = RunLogLevel.Info)

    /** Writes the run end and closes the file; [outcome] is a run outcome name such as `StoppedByUser`. */
    fun end(outcome: String)
}

fun RunLog.debug(source: RunLogSource, event: String, data: Map<String, Any?> = emptyMap()) = event(source, event, data, RunLogLevel.Debug)

fun RunLog.warn(source: RunLogSource, event: String, data: Map<String, Any?> = emptyMap()) = event(source, event, data, RunLogLevel.Warn)

/** A caught error: its class and a safe one-line message, never a stack trace. */
fun RunLog.error(source: RunLogSource, event: String, error: Throwable, data: Map<String, Any?> = emptyMap()) =
    event(source, event, data + RunLogSanitizer.errorData(error), RunLogLevel.Error)

/** For code that runs without the app shell (tests, mobile). */
object NoRunLog : RunLog {
    override val isOpen = false
    override fun start(routineKind: String, routineName: String, settings: Map<String, Any?>) = Unit
    override fun event(source: RunLogSource, event: String, data: Map<String, Any?>, level: RunLogLevel) = Unit
    override fun end(outcome: String) = Unit
}

/** The run-log folder on this computer, for Settings' "Open logs folder". */
interface RunLogFiles {
    val isAvailable: Boolean

    /** Shows the folder; returns a problem to explain, or null when shown. */
    suspend fun openFolder(): String?
}

object NoRunLogFiles : RunLogFiles {
    override val isAvailable = false
    override suspend fun openFolder(): String? = "Run logs are kept on desktop computers only."
}
