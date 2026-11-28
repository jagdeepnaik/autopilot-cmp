package com.jagdeep.shared.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class RunLogLinesTest {
    private val lines = RunLogLines(RunLogSanitizer())

    @Test fun given_epoch_millis_when_formatted_then_iso_utc_with_milliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", RunLogLines.isoMillis(0))
        assertEquals("2026-11-28T10:00:03.120Z", RunLogLines.isoMillis(1_795_860_003_120))
        assertEquals("2024-02-29T23:59:59.999Z", RunLogLines.isoMillis(1_709_251_199_999))
        assertEquals("2026-11-28", RunLogLines.dateFolder(1_795_860_003_120))
    }

    @Test fun given_a_run_when_lines_are_written_then_they_match_the_contract_shape() {
        assertEquals(
            """{"type":"run.start","runId":"r1","at":"1970-01-01T00:00:00.000Z","routineKind":"CourseAutomation","routineName":"SMY","appVersion":"1.1.0+build.1.gabc","os":"macOS 26.1 arm64","settings":{"assessmentMode":"Automatic"}}""",
            lines.header("r1", 0, "CourseAutomation", "SMY", "1.1.0+build.1.gabc", "macOS 26.1 arm64", mapOf("assessmentMode" to "Automatic")),
        )
        assertEquals(
            """{"type":"event","at":"1970-01-01T00:00:03.120Z","seq":14,"level":"info","source":"video","event":"video.state","data":{"state":"paused","currentTime":0,"duration":612.4,"errorCode":null,"muted":true}}""",
            lines.event(3_120, 14, RunLogLevel.Info, RunLogSource.Video, "video.state", mapOf("state" to "paused", "currentTime" to 0.0, "duration" to 612.4, "errorCode" to null, "muted" to true)),
        )
        assertEquals(
            """{"type":"run.end","at":"1970-01-01T00:00:10.000Z","outcome":"StoppedByUser","events":2310,"durationMs":10000}""",
            lines.end(10_000, "StoppedByUser", 2310, 10_000),
        )
    }

    @Test fun given_quotes_and_control_characters_when_written_then_json_is_escaped() {
        val line = lines.event(0, 1, RunLogLevel.Warn, RunLogSource.App, "bad name!", mapOf("reason" to "say \"hi\" \\ now"))
        assertEquals("""{"type":"event","at":"1970-01-01T00:00:00.000Z","seq":1,"level":"warn","source":"app","event":"invalid.name","data":{"reason":"say \"hi\" \\ now"}}""", line)
    }
}
