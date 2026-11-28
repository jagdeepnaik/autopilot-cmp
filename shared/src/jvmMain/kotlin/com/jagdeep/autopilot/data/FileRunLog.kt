package com.jagdeep.autopilot.data

import com.jagdeep.home.domain.cosmos.RunLogMark
import com.jagdeep.home.domain.cosmos.RunLogOutbox
import com.jagdeep.shared.diagnostics.RunLog
import com.jagdeep.shared.diagnostics.RunLogLevel
import com.jagdeep.shared.diagnostics.RunLogLines
import com.jagdeep.shared.diagnostics.RunLogSanitizer
import com.jagdeep.shared.diagnostics.RunLogSource
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Writes each routine run to `<root>/<YYYY-MM-DD>/<runId>.jsonl` (diagnostics contract) as it happens,
 * flushed at least every [flushMillis], so a hang or crash still leaves the log. Every line goes
 * through [RunLogLines] and its sanitiser. Writing never throws: a disk problem only stops this run's log.
 */
internal class FileRunLog(
    private val root: File,
    private val appVersion: String,
    private val os: String,
    sanitizer: RunLogSanitizer,
    private val now: () -> Long = System::currentTimeMillis,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
    flushMillis: Long = 500,
    /** Caps one run's file; later events are counted, not written. */
    private val maxEvents: Long = 500_000,
) : RunLog, AutoCloseable {
    private val lines = RunLogLines(sanitizer)
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var runId: String? = null
    private var startedAt = 0L
    private var seq = 0L
    private var dropped = 0L
    private var dirty = false
    private val mutableFinished = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val flusher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "run-log-flush").apply { isDaemon = true }
    }

    /** The run id of each run whose log has just ended, so it can be uploaded. */
    val finished: SharedFlow<String> = mutableFinished.asSharedFlow()

    /** The run being written, which is never offered for upload. */
    val activeRunId: String? get() = synchronized(lock) { runId }

    override val isOpen: Boolean get() = synchronized(lock) { writer != null }

    init {
        flusher.scheduleWithFixedDelay(::flush, flushMillis, flushMillis, TimeUnit.MILLISECONDS)
    }

    override fun start(routineKind: String, routineName: String, settings: Map<String, Any?>) {
        synchronized(lock) {
            if (writer != null) endLocked("Interrupted")
            val id = newRunId()
            val at = now()
            try {
                val folder = File(root, RunLogLines.dateFolder(at))
                if (!folder.isDirectory && !folder.mkdirs()) return
                val output = BufferedWriter(OutputStreamWriter(FileOutputStream(File(folder, "$id.jsonl"), true), Charsets.UTF_8), 64 * 1024)
                writer = output
                runId = id
                startedAt = at
                seq = 0
                dropped = 0
                write(lines.header(id, at, routineKind, routineName, appVersion, os, settings))
                output.flush()
            } catch (_: Exception) { closeQuietly() }
        }
    }

    override fun event(source: RunLogSource, event: String, data: Map<String, Any?>, level: RunLogLevel) {
        synchronized(lock) {
            if (writer == null) return
            if (seq >= maxEvents) { dropped++; return }
            seq++
            write(try { lines.event(now(), seq, level, source, event, data) } catch (_: Exception) { return })
            // Problems are written at once: they are what a hang leaves behind.
            if (level == RunLogLevel.Error) flushLocked()
        }
    }

    override fun end(outcome: String) {
        val id = synchronized(lock) { if (writer == null) null else endLocked(outcome) } ?: return
        mutableFinished.tryEmit(id)
    }

    private fun endLocked(outcome: String): String? {
        val id = runId
        if (dropped > 0) {
            seq++
            write(lines.event(now(), seq, RunLogLevel.Warn, RunLogSource.App, "log.eventsDropped", mapOf("count" to dropped)))
        }
        val at = now()
        write(lines.end(at, outcome, seq, at - startedAt))
        closeQuietly()
        return id
    }

    private fun write(line: String) {
        val output = writer ?: return
        try {
            output.write(line)
            output.write("\n")
            dirty = true
        } catch (_: Exception) { closeQuietly() }
    }

    fun flush() { synchronized(lock) { flushLocked() } }

    private fun flushLocked() {
        if (!dirty) return
        try { writer?.flush(); dirty = false } catch (_: Exception) { closeQuietly() }
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: Exception) { }
        writer = null
        runId = null
        dirty = false
    }

    /** Ends an open run (the app is closing) and stops the flusher. */
    override fun close() {
        if (isOpen) end("AppClosed")
        flusher.shutdown()
    }

    companion object {
        const val RETENTION_DAYS = 30
        private val DATE_FOLDER = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** Deletes run-log folders older than [days] (by their date name); returns how many folders went. */
        fun prune(root: File, nowMillis: Long, days: Int = RETENTION_DAYS): Int {
            val cutoff = RunLogLines.dateFolder(nowMillis - days * 86_400_000L)
            val old = root.listFiles { file -> file.isDirectory && DATE_FOLDER.matches(file.name) && file.name < cutoff }.orEmpty()
            return old.count { it.deleteRecursively() }
        }

        /** The operating system for the header, for example `macOS 26.1 aarch64`. */
        fun osDescription(): String {
            val name = System.getProperty("os.name").orEmpty()
            val family = when {
                name.startsWith("Mac", true) -> "macOS"
                name.startsWith("Windows", true) -> name
                name.contains("Linux", true) -> "Linux"
                else -> name
            }
            return "$family ${System.getProperty("os.version").orEmpty()} ${System.getProperty("os.arch").orEmpty()}".trim()
        }
    }
}

/**
 * The finished run logs in [root] that Cosmos hasn't received. A run's upload state is a small
 * sidecar next to its log (`<runId>.sent`, `.rejected` or `.unreadable`); the logs themselves stay
 * for 30 days either way.
 */
internal class FileRunLogOutbox(private val root: File, private val activeRunId: () -> String?) : RunLogOutbox {
    private fun logs(): List<File> = root.listFiles { file -> file.isDirectory }.orEmpty()
        .flatMap { folder -> folder.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }.orEmpty().toList() }
        .sortedWith(compareBy<File>({ it.parentFile.name }, { it.lastModified() }, { it.name }))

    private fun File.runId() = name.removeSuffix(".jsonl")
    private fun File.marked() = MARKS.any { File(parentFile, "${runId()}.$it").exists() }

    private fun unsentFiles(): List<File> {
        val active = activeRunId()
        return logs().filter { it.runId() != active && !it.marked() && RUN_ID.matches(it.runId()) }
    }

    override suspend fun unsent(limit: Int): List<String> = withContext(Dispatchers.IO) { unsentFiles().take(limit).map { it.runId() } }

    override suspend fun lines(runId: String): List<String>? = withContext(Dispatchers.IO) {
        logs().firstOrNull { it.runId() == runId }?.let { file -> runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() }
    }

    override suspend fun mark(runId: String, mark: RunLogMark) = withContext(Dispatchers.IO) {
        val file = logs().firstOrNull { it.runId() == runId } ?: return@withContext
        runCatching { File(file.parentFile, "$runId.${mark.name.lowercase()}").createNewFile() }
        Unit
    }

    override suspend fun waitingCount(): Int = withContext(Dispatchers.IO) { unsentFiles().size }

    private companion object {
        val MARKS = RunLogMark.entries.map { it.name.lowercase() }
        val RUN_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
