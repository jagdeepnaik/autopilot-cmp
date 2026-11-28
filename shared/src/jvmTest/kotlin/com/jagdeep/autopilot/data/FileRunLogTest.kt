package com.jagdeep.autopilot.data

import com.jagdeep.home.domain.compass.RunLogMark
import com.jagdeep.home.domain.compass.RunLogUploadBody
import com.jagdeep.shared.diagnostics.RunLogLevel
import com.jagdeep.shared.diagnostics.RunLogSanitizer
import com.jagdeep.shared.diagnostics.RunLogSource
import com.jagdeep.autopilot.platform.desktopUpdatePlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class FileRunLogTest {
    private val root: File = Files.createTempDirectory("run-logs").toFile()
    private var clock = 1_795_860_000_000L // 2026-11-28T10:00:00Z
    private var ids = 0
    private fun writer(flushMillis: Long = 50) = FileRunLog(root, "1.1.0+build.1.gabc", "macOS 26.1 aarch64",
        RunLogSanitizer(listOf("/Users/tester")), now = { clock }, newRunId = { "run-${++ids}" }, flushMillis = flushMillis)

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    @Test fun given_a_run_when_written_then_its_file_has_header_events_with_rising_seq_and_end() {
        val log = writer()
        log.start("CourseAutomation", "SMY", mapOf("assessmentMode" to "Automatic"))
        clock += 500
        log.event(RunLogSource.Video, "video.state", mapOf("state" to "paused", "readyState" to 1))
        log.event(RunLogSource.Page, "page.state", mapOf("url" to "https://secret.example/page", "reason" to "at /Users/tester/x"))
        clock += 1_500
        log.end("StoppedByUser")
        log.close()
        val file = File(root, "2026-11-28/run-1.jsonl")
        val lines = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("run.start", "event", "event", "run.end"), lines.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("run-1", lines[0].getValue("runId").jsonPrimitive.content)
        assertEquals("2026-11-28T10:00:00.000Z", lines[0].getValue("at").jsonPrimitive.content)
        assertEquals(listOf(1L, 2L), lines.subList(1, 3).map { it.getValue("seq").jsonPrimitive.long })
        assertEquals(2L, lines[3].getValue("events").jsonPrimitive.long)
        assertEquals(2_000L, lines[3].getValue("durationMs").jsonPrimitive.long)
        // The sanitiser ran at write time.
        val text = file.readText()
        assertFalse(text.contains("secret.example"))
        assertFalse(text.contains("/Users/tester"))
        assertNotNull(RunLogUploadBody.build("run-1", file.readLines()))
    }

    @Test fun given_an_open_run_when_a_second_passes_then_its_events_are_on_disk_without_ending() {
        val log = writer(flushMillis = 100)
        log.start("Click", "Clicker")
        log.event(RunLogSource.Click, "click", mapOf("count" to 1), RunLogLevel.Debug)
        Thread.sleep(1_000)
        val lines = File(root, "2026-11-28/run-1.jsonl").readLines()
        assertEquals(2, lines.size)
        assertTrue(log.isOpen)
        log.close()
    }

    @Test fun given_a_new_run_while_one_is_open_then_the_old_one_ends_as_interrupted() {
        val log = writer()
        log.start("Click", "A")
        log.start("Click", "B")
        log.close()
        assertTrue(File(root, "2026-11-28/run-1.jsonl").readLines().last().contains("\"outcome\":\"Interrupted\""))
        assertTrue(File(root, "2026-11-28/run-2.jsonl").readLines().last().contains("\"outcome\":\"AppClosed\""))
    }

    @Test fun given_events_without_an_open_run_then_nothing_is_written() {
        val log = writer()
        log.event(RunLogSource.Bridge, "bridge.connected")
        log.end("Completed")
        log.close()
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun given_folders_older_than_thirty_days_when_pruned_then_only_they_are_deleted() {
        listOf("2026-10-28", "2026-10-29", "2026-11-27", "not-a-date").forEach { File(root, it).mkdirs(); File(root, "$it/r.jsonl").writeText("{}") }
        assertEquals(1, FileRunLog.prune(root, clock))
        assertEquals(setOf("2026-10-29", "2026-11-27", "not-a-date"), root.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun given_finished_runs_when_the_outbox_is_read_then_the_open_run_is_skipped_and_marks_are_kept() = runBlocking {
        val log = writer()
        log.start("Click", "A"); log.end("Completed")
        clock += 1_000
        log.start("Click", "B"); log.end("Completed")
        log.start("Click", "C") // still running
        val outbox = FileRunLogOutbox(root) { log.activeRunId }
        assertEquals(listOf("run-1", "run-2"), outbox.unsent(10))
        assertEquals(2, outbox.waitingCount())
        outbox.mark("run-1", RunLogMark.Sent)
        assertTrue(File(root, "2026-11-28/run-1.sent").exists())
        assertEquals(listOf("run-2"), outbox.unsent(10))
        assertNotNull(outbox.lines("run-2"))
        assertNull(outbox.lines("missing"))
        log.close()
        assertEquals(listOf("run-2", "run-3"), outbox.unsent(10))
    }

    @Test fun given_os_names_then_the_update_platform_matches_the_contract() {
        assertEquals("macos", desktopUpdatePlatform("Mac OS X"))
        assertEquals("windows", desktopUpdatePlatform("Windows 11"))
        assertEquals("linux", desktopUpdatePlatform("Linux"))
        assertNull(desktopUpdatePlatform("FreeBSD"))
    }
}
