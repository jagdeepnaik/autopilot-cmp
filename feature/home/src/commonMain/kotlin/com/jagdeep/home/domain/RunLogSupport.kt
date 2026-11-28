package com.jagdeep.home.domain

import com.jagdeep.shared.diagnostics.RunLog
import com.jagdeep.shared.diagnostics.RunLogLevel
import com.jagdeep.shared.diagnostics.RunLogSource

/*
 * Helpers that turn what a routine sees into run-log events (private/docs/shared/02_DIAGNOSTICS_AND_DOWNLOADS_API.md)
 * without flooding the file: state changes are logged as they happen, and anything that changes
 * many times a second (a video's current time, page readings) is collapsed into a heartbeat.
 */

/** What a routine settings header may say about [routine]: numbers, choices and yes/no values only. */
internal fun runLogSettings(routine: Routine): Map<String, Any?> = when (routine) {
    is ClickRoutine -> mapOf(
        "target" to if (routine.target is ClickTarget.AtPoints) "points" else "pointer",
        "points" to (routine.target as? ClickTarget.AtPoints)?.points?.size,
        "button" to routine.button.name,
        "clicksPerAction" to routine.clicksPerAction,
        "intervalMillis" to routine.intervalMillis,
        "randomExtraMillis" to routine.randomExtraMillis,
        "limit" to when (routine.limit) { RunLimit.UntilStopped -> "untilStopped"; is RunLimit.Clicks -> "clicks"; is RunLimit.Duration -> "duration"; is RunLimit.Passes -> "passes" },
        "limitCount" to when (val limit = routine.limit) { is RunLimit.Clicks -> limit.count; is RunLimit.Passes -> limit.count; is RunLimit.Duration -> limit.millis; RunLimit.UntilStopped -> null },
        "onlyInOneApp" to (routine.onlyInApp != null),
        "startDelaySeconds" to routine.startDelaySeconds,
    )
    is ScrollClickRoutine -> mapOf(
        "rounds" to routine.rounds,
        "pixelsPerSecond" to routine.pixelsPerSecond,
        "watchVideos" to routine.watchVideos,
        "steps" to routine.steps.size,
        "labelSteps" to routine.steps.count { it.text != null },
    )
    is CourseAutomationRoutine -> mapOf(
        "assessmentMode" to routine.assessmentMode.name,
        "learningOrder" to routine.learningOrder.name,
        "waitAfterClickMillis" to routine.waitAfterClickMillis,
        "playbackSpeed" to routine.playbackSpeed,
        "answerAssessments" to routine.answerAssessments,
        "answerQuizzes" to routine.answerQuizzes,
        "autoDownloadCertificates" to routine.autoDownloadCertificates,
    )
    is DgsELearningRoutine -> mapOf(
        "requiredHours" to routine.requiredHours,
        "creditedHours" to routine.creditedHours,
        "pixelsPerSecond" to routine.pixelsPerSecond,
        "playbackSpeed" to routine.playbackSpeed,
        "waitAfterClickMillis" to routine.waitAfterClickMillis,
    )
    is OceanTGRoutine -> mapOf(
        "waitAfterClickMillis" to routine.waitAfterClickMillis,
        "answerPracticeQuestions" to routine.answerPracticeQuestions,
        "courseCodes" to routine.courseCodes.size,
    )
    is MarineSamrajRoutine -> mapOf(
        "maxPages" to routine.maxPages,
        "clickDelayMillis" to routine.clickDelayMillis,
        "continueSavedCollection" to routine.continueSavedCollection,
        "includeBookmarks" to routine.includeBookmarks,
    )
}

/**
 * A routine's status line as a reason template: the step it is on and why, with page-derived parts
 * removed. Quoted text (a question) and [sensitive] values (profile, module and course names) are
 * replaced, and numbers become `#` so a countdown doesn't log a new reason every second.
 */
internal fun reasonTemplate(detail: String, sensitive: List<String?> = emptyList()): String {
    var text = detail
    sensitive.filterNotNull().map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }
        .forEach { text = text.replace(it, "{…}", ignoreCase = true) }
    text = QUOTED.replace(text, "“…”")
    text = DIGITS.replace(text, "#")
    return text
}

private val QUOTED = Regex("“[^”]*”|\"[^\"]*\"")
private val DIGITS = Regex("[0-9]+(?:[.,][0-9]+)?")

/** One video as the page reports it; [id] is an opaque page-local element id. */
internal data class VideoSample(
    val id: String,
    val state: String? = null,
    val paused: Boolean? = null,
    val ended: Boolean? = null,
    val currentTime: Double? = null,
    val duration: Double? = null,
    val readyState: Int? = null,
    val networkState: Int? = null,
    val errorCode: Int? = null,
    val muted: Boolean? = null,
    val buffering: Boolean? = null,
) {
    /** Everything except the playing position, so time updates alone are not state changes. */
    val signature get() = listOf(state, paused, ended, duration?.let { kotlin.math.round(it * 10) / 10 }, readyState, networkState, errorCode, muted, buffering)

    /** Playing as far as the page says: not paused, not ended. */
    val playing get() = state == "playing" || (paused == false && ended != true)

    fun data(): Map<String, Any?> = linkedMapOf(
        "videoId" to id, "state" to (state ?: when { ended == true -> "ended"; paused == true -> "paused"; paused == false -> "playing"; else -> null }),
        "currentTime" to currentTime?.let { kotlin.math.round(it * 10) / 10 }, "duration" to duration,
        "readyState" to readyState, "networkState" to networkState, "errorCode" to errorCode, "muted" to muted, "buffering" to buffering,
    )
}

/**
 * Logs every video state change at once and the playing position only every [heartbeatMillis]:
 * a page reporting its time every 250 ms gives one heartbeat per five seconds, not twenty lines.
 * A heartbeat on a playing video whose time hasn't moved is a warning: that is a stuck player.
 */
internal class VideoLogSampler(private val heartbeatMillis: Long = 5_000) {
    private val last = mutableMapOf<String, VideoSample>()
    private val beatTime = mutableMapOf<String, Double?>()
    private var lastBeatAt: Long? = null

    fun reset() { last.clear(); beatTime.clear(); lastBeatAt = null }

    fun observe(now: Long, videos: List<VideoSample>, log: RunLog, source: String) {
        val seen = videos.distinctBy { it.id }.take(MAX_VIDEOS)
        for (video in seen) {
            val previous = last[video.id]
            if (previous == null || previous.signature != video.signature) {
                log.event(RunLogSource.Video, "video.state", video.data() + ("page" to source),
                    if (video.errorCode != null) RunLogLevel.Warn else RunLogLevel.Info)
                if (previous == null) beatTime[video.id] = video.currentTime
            }
            last[video.id] = video
        }
        (last.keys - seen.map { it.id }.toSet()).toList().forEach { gone ->
            last.remove(gone); beatTime.remove(gone)
            log.event(RunLogSource.Video, "video.gone", mapOf("videoId" to gone, "page" to source), RunLogLevel.Debug)
        }
        heartbeat(now, log)
    }

    /** Also called by the run's ticker, so a page that stops reporting still gets heartbeats. */
    fun heartbeat(now: Long, log: RunLog) {
        if (last.isEmpty()) { lastBeatAt = null; return }
        val since = lastBeatAt
        if (since == null) { lastBeatAt = now; return }
        if (now - since < heartbeatMillis) return
        lastBeatAt = now
        for ((id, video) in last) {
            val before = beatTime[id]
            val advanced = if (before != null && video.currentTime != null) video.currentTime - before else null
            beatTime[id] = video.currentTime
            val stuck = video.playing && video.ended != true && advanced != null && advanced < 0.5
            log.event(RunLogSource.Video, if (stuck) "video.notAdvancing" else "video.heartbeat",
                mapOf("videoId" to id, "state" to video.data()["state"], "currentTime" to video.data()["currentTime"],
                    "advancedSeconds" to advanced?.let { kotlin.math.round(it * 10) / 10 }, "intervalMs" to now - since),
                if (stuck) RunLogLevel.Warn else RunLogLevel.Debug)
        }
    }

    private companion object { const val MAX_VIDEOS = 8 }
}

/**
 * Messages from the extension: the first of each type is logged in full (type and size), then each
 * type is summarised every [summaryMillis] with its count and bytes. Gaps longer than
 * [silenceMillis] are logged when they start and when messages resume.
 */
internal class BridgeLogStats(private val summaryMillis: Long = 5_000, private val silenceMillis: Long = 5_000) {
    private class Counts(var count: Int = 0, var bytes: Long = 0, var maxBytes: Int = 0)
    private val seen = mutableSetOf<String>()
    private val counts = linkedMapOf<String, Counts>()
    private var summarisedAt: Long? = null
    private var lastMessageAt: Long? = null
    private var silentSince: Long? = null
    var received = 0L
        private set

    fun reset(now: Long) { seen.clear(); counts.clear(); summarisedAt = now; lastMessageAt = now; silentSince = null; received = 0 }

    fun incoming(now: Long, type: String, bytes: Int, log: RunLog) {
        received++
        silentSince?.let { since ->
            log.event(RunLogSource.Bridge, "bridge.resumed", mapOf("silentMs" to now - (lastMessageAt ?: since), "type" to type))
            silentSince = null
        }
        lastMessageAt = now
        if (seen.add(type)) log.event(RunLogSource.Extension, "extension.message", mapOf("type" to type, "bytes" to bytes, "first" to true), RunLogLevel.Debug)
        else counts.getOrPut(type) { Counts() }.apply { count++; this.bytes += bytes; maxBytes = maxOf(maxBytes, bytes) }
        tick(now, log)
    }

    fun tick(now: Long, log: RunLog) {
        val last = lastMessageAt
        if (last != null && silentSince == null && now - last >= silenceMillis) {
            silentSince = last
            log.event(RunLogSource.Bridge, "bridge.silent", mapOf("sinceMs" to now - last), RunLogLevel.Warn)
        }
        val since = summarisedAt ?: now.also { summarisedAt = it }
        if (now - since < summaryMillis) return
        summarisedAt = now
        counts.filterValues { it.count > 0 }.forEach { (type, value) ->
            log.event(RunLogSource.Extension, "extension.messages", mapOf("type" to type, "count" to value.count, "bytes" to value.bytes, "maxBytes" to value.maxBytes, "intervalMs" to now - since), RunLogLevel.Debug)
        }
        counts.clear()
    }
}

/**
 * Clicks and the waits between them: the first [detailed] of each are logged one by one, then a
 * progress line every [summaryMillis] with the count, so a routine clicking ten times a second
 * writes a few lines a minute instead of thousands.
 */
internal class ClickLogSampler(private val now: () -> Long, private val detailed: Int = 10, private val summaryMillis: Long = 5_000) {
    private var clicksLogged = 0
    private var waitsLogged = 0
    private var summaryAt: Long? = null
    private var clicksAtSummary = 0L

    fun clicked(count: Long, log: RunLog) {
        if (clicksLogged < detailed) { clicksLogged++; log.event(RunLogSource.Click, "click", mapOf("count" to count), RunLogLevel.Debug) }
        val at = now()
        val since = summaryAt ?: at.also { summaryAt = it; clicksAtSummary = count }
        if (at - since >= summaryMillis) {
            log.event(RunLogSource.Click, "click.progress", mapOf("clicks" to count, "clicksInInterval" to count - clicksAtSummary, "intervalMs" to at - since))
            summaryAt = at
            clicksAtSummary = count
        }
    }

    fun waiting(millis: Long, clicks: Long, log: RunLog) {
        if (waitsLogged >= detailed) return
        waitsLogged++
        log.event(RunLogSource.Click, "click.wait", mapOf("waitMs" to millis.coerceAtLeast(0), "clicks" to clicks), RunLogLevel.Debug)
    }
}

/** The type field of an extension message, read without parsing the whole body. */
internal fun messageType(raw: String): String =
    MESSAGE_TYPE.find(raw.take(400))?.groupValues?.get(1)?.take(40) ?: "unknown"

private val MESSAGE_TYPE = Regex("\"type\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,40})\"")

/**
 * A routine file's `question_key` for [question] and [options]: SHA-256 (lowercase hex) of the
 * normalised question, U+001F, then the sorted normalised options joined by U+001E — the same key
 * as `QuestionAnswerText.questionKey`, so a logged key finds the question in the Q&A file and on
 * Cosmos without the log holding its text.
 */
internal fun runLogQuestionKey(question: String, options: List<String>): String {
    fun normalize(value: String) = value.replace(' ', ' ').replace(Regex("\\s+"), " ").trim().lowercase()
    fun option(value: String) = normalize(value.replace(' ', ' ').trim().replace(Regex("^\\d+[.)]\\s+"), ""))
    return Sha256.hex((normalize(question) + "\u001f" + options.map(::option).sorted().joinToString("\u001e")).encodeToByteArray())
}

/** A small SHA-256 for common code (question keys only; not used for security). */
internal object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun hex(input: ByteArray): String = digest(input).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun digest(input: ByteArray): ByteArray {
        val h = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                w[i] = ((padded[chunk + 4 * i].toInt() and 0xff) shl 24) or ((padded[chunk + 4 * i + 1].toInt() and 0xff) shl 16) or
                    ((padded[chunk + 4 * i + 2].toInt() and 0xff) shl 8) or (padded[chunk + 4 * i + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32) { i -> (h[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
    }
}
