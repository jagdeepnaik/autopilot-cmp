package com.jagdeep.shared.diagnostics

/**
 * The three kinds of run-log line, one JSON object each, exactly as the diagnostics contract shows:
 * `run.start` (header), `event`, then `run.end`. Every string and `data` value goes through
 * [sanitizer] here, so nothing unsafe can reach the file.
 */
class RunLogLines(private val sanitizer: RunLogSanitizer) {
    fun header(runId: String, atMillis: Long, routineKind: String, routineName: String, appVersion: String, os: String, settings: Map<String, Any?>): String =
        json(linkedMapOf(
            "type" to "run.start",
            "runId" to runId,
            "at" to isoMillis(atMillis),
            "routineKind" to sanitizer.text(routineKind, 60),
            "routineName" to sanitizer.text(routineName),
            "appVersion" to sanitizer.text(appVersion, 80),
            "os" to sanitizer.text(os, 80),
            "settings" to sanitizer.data(settings),
        ))

    fun event(atMillis: Long, seq: Long, level: RunLogLevel, source: RunLogSource, event: String, data: Map<String, Any?>): String =
        json(linkedMapOf(
            "type" to "event",
            "at" to isoMillis(atMillis),
            "seq" to seq,
            "level" to level.wire,
            "source" to source.wire,
            "event" to eventName(event),
            "data" to sanitizer.data(data),
        ))

    fun end(atMillis: Long, outcome: String, events: Long, durationMillis: Long): String =
        json(linkedMapOf(
            "type" to "run.end",
            "at" to isoMillis(atMillis),
            "outcome" to sanitizer.text(outcome, 60),
            "events" to events,
            "durationMs" to durationMillis.coerceAtLeast(0),
        ))

    companion object {
        private val EVENT_NAME = Regex("[a-z][A-Za-z0-9_.]{0,63}")

        /** Event names are code identifiers such as `video.state`; anything else is replaced. */
        fun eventName(name: String) = if (EVENT_NAME.matches(name)) name else "invalid.name"

        /** UTC ISO-8601 with milliseconds, for example 2026-11-28T10:00:03.120Z. */
        fun isoMillis(epochMillis: Long): String {
            val days = floorDiv(epochMillis, 86_400_000L)
            val inDay = epochMillis - days * 86_400_000L
            // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
            val z = days + 719_468
            val era = floorDiv(z, 146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yoe + era * 400 + if (month <= 2) 1 else 0
            val hours = inDay / 3_600_000
            val minutes = inDay / 60_000 % 60
            val seconds = inDay / 1_000 % 60
            val millis = inDay % 1_000
            return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(hours, 2)}:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(millis, 3)}Z"
        }

        /** The run's folder name: its UTC start date. */
        fun dateFolder(epochMillis: Long) = isoMillis(epochMillis).substring(0, 10)

        private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
        private fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')

        internal fun json(values: Map<String, Any?>): String = buildString { appendJson(values) }

        private fun StringBuilder.appendJson(value: Any?) {
            when (value) {
                null -> append("null")
                is String -> appendString(value)
                is Boolean, is Int, is Long -> append(value.toString())
                is Double -> if (value.isFinite()) append(if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()) else append("null")
                is Number -> appendJson(value.toDouble())
                is Map<*, *> -> {
                    append('{')
                    var first = true
                    for ((key, item) in value) {
                        if (!first) append(',')
                        first = false
                        appendString(key.toString())
                        append(':')
                        appendJson(item)
                    }
                    append('}')
                }
                else -> appendString(value.toString())
            }
        }

        private fun StringBuilder.appendString(text: String) {
            append('"')
            for (char in text) {
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\t' -> append("\\t")
                    char < ' ' || char == ' ' || char == ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
