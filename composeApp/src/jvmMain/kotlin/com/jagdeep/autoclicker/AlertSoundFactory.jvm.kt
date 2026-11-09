package com.jagdeep.autoclicker

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.home.domain.AssessmentAlerts
import com.jagdeep.home.domain.AssessmentAlertKind
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlin.math.PI
import kotlin.math.sin

internal actual fun withAlertSound(automation: DesktopAutomation): DesktopAutomation =
    AudibleDesktopAutomation(automation)

internal actual fun createAssessmentAlerts(automation: DesktopAutomation): AssessmentAlerts = AssessmentAlerts { kind ->
    if (automation is AudibleDesktopAutomation) automation.play(kind) else automation.playAlert()
}

/** Two short notes use normal audio output rather than the separately muted system beep. */
internal fun assessmentAlertPcm(kind: AssessmentAlertKind = AssessmentAlertKind.Question): ByteArray {
    val rate = 22_050
    val noteSamples = rate / 5
    val gapSamples = rate / 12
    val notes = if (kind == AssessmentAlertKind.Question) listOf(880.0, 1_175.0) else listOf(660.0, 520.0, 390.0)
    val samples = ShortArray(noteSamples * notes.size + gapSamples * (notes.size - 1))
    for ((note, frequency) in notes.withIndex()) {
        val start = note * (noteSamples + gapSamples)
        for (i in 0 until noteSamples) {
            val envelope = minOf(1.0, i / 220.0, (noteSamples - 1 - i) / 220.0)
            samples[start + i] = (sin(2 * PI * frequency * i / rate) * envelope * 16_000).toInt().toShort()
        }
    }
    return ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
}

private class AudibleDesktopAutomation(private val delegate: DesktopAutomation) : DesktopAutomation by delegate {
    private val clips = mutableSetOf<Clip>()
    private var closed = false

    override fun playAlert() = play(AssessmentAlertKind.Question)

    @Synchronized
    fun play(kind: AssessmentAlertKind) {
        check(!closed) { "Audio output is closed" }
        // A second request must not overlap an alert still playing.
        if (clips.isNotEmpty()) return
        val clip = AudioSystem.getClip()
        try {
            val bytes = assessmentAlertPcm(kind)
            clip.open(AudioFormat(22_050f, 16, 1, true, false), bytes, 0, bytes.size)
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    synchronized(this) { clips.remove(clip) }
                    clip.close()
                }
            }
            clips.add(clip)
            clip.start()
        } catch (error: Exception) {
            clips.remove(clip)
            clip.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clips.toList().forEach { it.close() }
        clips.clear()
        delegate.close()
    }
}
