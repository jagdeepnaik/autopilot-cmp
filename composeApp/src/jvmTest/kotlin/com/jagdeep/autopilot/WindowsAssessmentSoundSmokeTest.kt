package com.jagdeep.autopilot

import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.home.domain.AssessmentAlertKind
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/** Explicit hardware check: run on the signed-in desktop, never in an ordinary test run. */
class WindowsAssessmentSoundSmokeTest {
    @Test
    fun `play the real reminder and failure alert on the Windows desktop`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getenv("AUTOPILOT_TEST_AUDIO") in setOf("1", "stall"))
        println("Available audio mixers: " + AudioSystem.getMixerInfo().joinToString { it.name })
        val automation = withAlertSound(UnsupportedDesktopAutomation)
        try {
            val alerts = createAssessmentAlerts(automation)
            if (System.getenv("AUTOPILOT_TEST_AUDIO") == "stall") {
                alerts.play(AssessmentAlertKind.Stalled)
                Thread.sleep(1400)
                println("Production inactivity alert played without an audio API error.")
                return
            }
            alerts.play(AssessmentAlertKind.Question)
            // Deliberate real-time playback in this opt-in hardware test, not a business-logic test.
            Thread.sleep(1000)
            alerts.play(AssessmentAlertKind.Failure)
            Thread.sleep(1200)
            println("Both production alert patterns played without an audio API error. Audibility needs user confirmation.")
        } finally { automation.close() }
    }
}
