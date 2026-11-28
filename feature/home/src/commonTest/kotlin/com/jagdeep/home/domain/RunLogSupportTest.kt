package com.jagdeep.home.domain

import com.jagdeep.shared.diagnostics.RunLog
import com.jagdeep.shared.diagnostics.RunLogLevel
import com.jagdeep.shared.diagnostics.RunLogSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records what a routine would write, for tests. */
internal class RecordingRunLog : RunLog {
    data class Line(val type: String, val source: RunLogSource? = null, val event: String = "", val data: Map<String, Any?> = emptyMap(), val level: RunLogLevel = RunLogLevel.Info)
    val lines = mutableListOf<Line>()
    override var isOpen = false
    override fun start(routineKind: String, routineName: String, settings: Map<String, Any?>) {
        if (isOpen) end("Interrupted")
        isOpen = true
        lines += Line("run.start", event = routineKind, data = settings)
    }
    override fun event(source: RunLogSource, event: String, data: Map<String, Any?>, level: RunLogLevel) {
        if (isOpen) lines += Line("event", source, event, data, level)
    }
    override fun end(outcome: String) {
        if (!isOpen) return
        isOpen = false
        lines += Line("run.end", event = outcome)
    }
    fun events(name: String) = lines.filter { it.type == "event" && it.event == name }
}

class RunLogSupportTest {
    @Test fun given_a_video_reporting_every_250ms_when_sampled_then_state_once_and_one_heartbeat_per_five_seconds() {
        val log = RecordingRunLog().apply { start("CourseAutomation", "R") }
        val sampler = VideoLogSampler()
        for (tick in 0..40) { // ten seconds
            sampler.observe(tick * 250L, listOf(VideoSample("v1", paused = false, ended = false, currentTime = tick * 0.25, duration = 600.0, readyState = 4)), log, "course")
        }
        assertEquals(1, log.events("video.state").size)
        assertEquals(2, log.events("video.heartbeat").size)
        assertTrue(log.events("video.notAdvancing").isEmpty())
    }

    @Test fun given_a_video_changes_state_when_sampled_then_each_change_is_logged_at_once() {
        val log = RecordingRunLog().apply { start("CourseAutomation", "R") }
        val sampler = VideoLogSampler()
        sampler.observe(0, listOf(VideoSample("v1", paused = true, readyState = 1, networkState = 2, currentTime = 0.0)), log, "course")
        sampler.observe(250, listOf(VideoSample("v1", paused = false, readyState = 4, networkState = 1, currentTime = 0.1)), log, "course")
        sampler.observe(500, listOf(VideoSample("v1", paused = false, readyState = 4, networkState = 1, errorCode = 2, currentTime = 0.1)), log, "course")
        sampler.observe(750, emptyList(), log, "course")
        assertEquals(3, log.events("video.state").size)
        assertEquals(RunLogLevel.Warn, log.events("video.state").last().level)
        assertEquals(1, log.events("video.gone").size)
    }

    @Test fun given_a_playing_video_whose_time_stops_when_sampled_then_a_warning_is_logged() {
        val log = RecordingRunLog().apply { start("CourseAutomation", "R") }
        val sampler = VideoLogSampler()
        for (tick in 0..24) sampler.observe(tick * 250L, listOf(VideoSample("v1", paused = false, ended = false, currentTime = 42.0)), log, "course")
        assertEquals(1, log.events("video.notAdvancing").size)
        assertEquals(RunLogLevel.Warn, log.events("video.notAdvancing").single().level)
    }

    @Test fun given_many_messages_of_one_type_when_counted_then_the_first_is_logged_and_the_rest_summarised() {
        val log = RecordingRunLog().apply { start("CourseAutomation", "R") }
        val stats = BridgeLogStats()
        stats.reset(0)
        for (i in 0 until 20) stats.incoming(i * 300L, "courseChunk", 1_000 + i, log)
        assertEquals(1, log.events("extension.message").size)
        val summary = log.events("extension.messages").single()
        assertEquals("courseChunk", summary.data["type"])
        assertTrue((summary.data["count"] as Int) in 15..19)
    }

    @Test fun given_messages_stop_when_ticking_then_silence_and_resumption_are_logged() {
        val log = RecordingRunLog().apply { start("CourseAutomation", "R") }
        val stats = BridgeLogStats()
        stats.reset(0)
        stats.incoming(100, "page", 50, log)
        stats.tick(7_000, log)
        stats.tick(8_000, log)
        stats.incoming(12_100, "page", 50, log)
        assertEquals(1, log.events("bridge.silent").size)
        assertEquals(12_000L, log.events("bridge.resumed").single().data["silentMs"])
    }

    @Test fun given_a_status_line_when_templated_then_question_text_names_and_numbers_are_removed() {
        assertEquals("Assessment paused: no exact saved answer for “…”. Check the question and options in {…}.",
            reasonTemplate("Assessment paused: no exact saved answer for “What is 2 + 2?”. Check the question and options in bank.json.", listOf("bank.json")))
        assertEquals("Waiting # seconds before the next action.", reasonTemplate("Waiting 12 seconds before the next action."))
        assertEquals("Detected signed-in Sagar Mein Yog profile {…}. Confirm it to continue.",
            reasonTemplate("Detected signed-in Sagar Mein Yog profile Jagdeep Singh. Confirm it to continue.", listOf("Jagdeep Singh")))
    }

    @Test fun given_a_message_when_its_type_is_read_then_only_the_type_is_taken() {
        assertEquals("courseChunk", messageType("""{"session":"s","type":"courseChunk","text":"secret"}"""))
        assertEquals("unknown", messageType("not json"))
    }

    @Test fun given_known_input_when_hashed_then_sha256_matches_the_standard_vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
    }

    @Test fun given_each_routine_kind_when_settings_are_logged_then_they_hold_no_text() {
        val routines = listOf(
            ClickRoutine("c", "Click", onlyInApp = "Google Chrome"), ScrollClickRoutine("s", "Scroll"),
            CourseAutomationRoutine("a", "SMY"), DgsELearningRoutine("d", "DGS"), OceanTGRoutine("o", "OLP"), MarineSamrajRoutine("m", "MS"),
        )
        routines.forEach { routine ->
            val settings = runLogSettings(routine)
            assertTrue(settings.isNotEmpty())
            assertTrue(settings.values.none { it is String && it.contains("Chrome") }, settings.toString())
        }
    }
}
