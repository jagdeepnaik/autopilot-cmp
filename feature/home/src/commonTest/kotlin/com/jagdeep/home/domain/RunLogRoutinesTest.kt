package com.jagdeep.home.domain

import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.shared.diagnostics.RunLogSource
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RunLogRoutinesTest {
    private class Bridge : LocalBridge {
        override val connected = MutableStateFlow(true)
        override val problem = MutableStateFlow<String?>(null)
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val incoming: Flow<String> = messages
        override fun publish(stateJson: String) = Unit
        override fun close() = Unit
    }

    private fun TestScope.page(log: RecordingRunLog, bridge: Bridge = Bridge(), automation: FakeAutomation = FakeAutomation()) =
        PageController(automation, bridge, flowOf(AppPreferences()), backgroundScope, FakeRunHistory(), { currentTime }, { "id" }, runLog = log)

    private fun RecordingRunLog.assertOneRun(kind: String, outcome: String) {
        assertEquals(listOf("run.start", "run.end"), lines.filter { it.type != "event" }.map { it.type })
        assertEquals(kind, lines.first().event)
        assertEquals(outcome, lines.last().event)
        assertTrue(events("routine.started").size + events("routine.refused").size == 1, lines.toString())
    }

    @Test fun given_each_chrome_routine_kind_when_run_and_stopped_then_its_log_has_a_start_and_an_end() = runTest {
        val routines = listOf(
            ScrollClickRoutine("s", "Scroll", points = listOf(ClickPoint(10, 10))), DgsELearningRoutine("d", "DGS"),
            CourseAutomationRoutine("a", "SMY"), OceanTGRoutine("o", "OLP"), MarineSamrajRoutine("m", "MS"),
        )
        for (routine in routines) {
            val log = RecordingRunLog()
            val controller = page(log)
            runCurrent()
            assertEquals(null, controller.start(routine), routine.kind.name)
            runCurrent()
            advanceTimeBy(1_500)
            controller.stop()
            runCurrent()
            log.assertOneRun(routine.kind.name, "StoppedByUser")
        }
    }

    @Test fun given_chrome_is_not_connected_when_a_routine_starts_then_the_refusal_is_its_own_short_run() = runTest {
        val log = RecordingRunLog()
        val bridge = Bridge().apply { connected.value = false }
        val controller = page(log, bridge)
        runCurrent()
        assertTrue(controller.start(CourseAutomationRoutine("a", "SMY")) != null)
        log.assertOneRun("CourseAutomation", "NotStarted")
        assertEquals("extensionNotConnected", log.events("routine.refused").single().data["reason"])
    }

    @Test fun given_a_chrome_routine_runs_when_messages_arrive_then_types_and_sizes_are_logged_without_their_text() = runTest {
        val log = RecordingRunLog()
        val bridge = Bridge()
        val controller = page(log, bridge)
        runCurrent()
        controller.start(OceanTGRoutine("o", "OLP"))
        runCurrent()
        bridge.messages.emit("""{"type":"found","label":"Next lesson about https://site.example/a"}""")
        runCurrent()
        val first = log.events("extension.message").single()
        assertEquals("found", first.data["type"])
        assertTrue(log.lines.none { line -> line.data.values.any { it is String && it.contains("https://site.example/a") && line.event == "extension.message" } })
        controller.stop(); runCurrent()
    }

    @Test fun given_a_click_routine_when_run_then_its_log_has_a_start_clicks_and_an_end() = runTest {
        val log = RecordingRunLog()
        val routine = ClickRoutine("r", "Clicker", intervalMillis = 100, startDelaySeconds = 1, limit = RunLimit.Clicks(30))
        val automation = FakeAutomation()
        val controller = ClickController(automation, FakeRoutineRepository(RoutineLibrary(listOf(routine), "r")), flowOf(AppPreferences(stopWhenPointerMoves = false)),
            FakeRunHistory(), backgroundScope, { currentTime }, { "run" }, runLog = log)
        controller.start(routine)
        advanceTimeBy(10_000)
        runCurrent()
        log.assertOneRun("Click", "Completed")
        // Sampled: the first clicks one by one, not all thirty.
        assertEquals(10, log.events("click").size)
        assertEquals(RunLogSource.Routine, log.events("routine.ended").single().source)
        assertEquals(30L, log.events("routine.ended").single().data["clicks"])
    }

    @Test fun given_a_click_routine_cancelled_in_its_countdown_then_its_log_still_ends() = runTest {
        val log = RecordingRunLog()
        val routine = ClickRoutine("r", "Clicker", startDelaySeconds = 3)
        val controller = ClickController(FakeAutomation(), FakeRoutineRepository(RoutineLibrary(listOf(routine), "r")), flowOf(AppPreferences()),
            FakeRunHistory(), backgroundScope, { currentTime }, { "run" }, runLog = log)
        controller.start(routine)
        advanceTimeBy(1_000)
        controller.stop()
        runCurrent()
        assertEquals("CancelledDuringCountdown", log.lines.last().event)
    }
}
