package com.jagdeep.autopilot

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.jagdeep.home.domain.AssessmentAlertKind

class AlertSoundTest {
    @Test
    fun `inactivity alert has its own longer pattern`() {
        val stalled = assessmentAlertPcm(AssessmentAlertKind.Stalled)
        assertFalse(stalled.contentEquals(assessmentAlertPcm(AssessmentAlertKind.Question)))
        assertFalse(stalled.contentEquals(assessmentAlertPcm(AssessmentAlertKind.Failure)))
        assertTrue(stalled.size / 2 / 22050.0 in 1.0..1.2)
    }
    @Test
    fun `given a failed assessment then its three-note pattern differs from a question reminder`() {
        val reminder = assessmentAlertPcm(AssessmentAlertKind.Question)
        val failure = assessmentAlertPcm(AssessmentAlertKind.Failure)
        assertFalse(reminder.contentEquals(failure))
        assertTrue(failure.size > reminder.size)
        assertTrue(failure.size / 2 / 22_050.0 in 0.7..0.9)
    }
    @Test
    fun `given the assessment alert then it contains two audible notes separated by silence`() {
        val bytes = assessmentAlertPcm()
        val samples = bytes.asList().chunked(2).map { pair ->
            ((pair[0].toInt() and 255) or (pair[1].toInt() shl 8)).toShort().toInt()
        }
        assertTrue(samples.size / 22_050.0 in 0.4..0.6)
        assertTrue(samples.take(4_000).maxOf { abs(it) } > 10_000)
        assertTrue(samples.takeLast(4_000).maxOf { abs(it) } > 10_000)
        assertTrue(samples.subList(4_500, 6_000).all { it == 0 })
        assertEquals(0, samples.first())
        assertEquals(0, samples.last())
        assertTrue(samples.all { abs(it) < 32_767 }, "No clipped samples")
    }
}
