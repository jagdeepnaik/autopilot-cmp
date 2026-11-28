package com.jagdeep.home.domain.cosmos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** How a finished run's log left the upload queue. */
enum class RunLogMark { Sent, Rejected, Unreadable }

/**
 * The run logs on this computer that Cosmos hasn't received yet (diagnostics contract). The open
 * run is never offered: only runs that ended, or were cut short when the app stopped.
 */
interface RunLogOutbox {
    /** Run ids not yet sent, oldest first. */
    suspend fun unsent(limit: Int): List<String>

    /** The run's lines as written, or null when its file is gone. */
    suspend fun lines(runId: String): List<String>?

    suspend fun mark(runId: String, mark: RunLogMark)

    suspend fun waitingCount(): Int
}

object NoRunLogOutbox : RunLogOutbox {
    override suspend fun unsent(limit: Int) = emptyList<String>()
    override suspend fun lines(runId: String): List<String>? = null
    override suspend fun mark(runId: String, mark: RunLogMark) = Unit
    override suspend fun waitingCount() = 0
}

/**
 * The upload body for `POST /api/diagnostics/runs`: `{ runId, header, events, end, truncated }`.
 * At most [MAX_EVENTS] events and [MAX_BYTES] once inflated; a longer run keeps its first and last
 * events and is marked truncated. A file without a readable header can't be uploaded (null).
 */
object RunLogUploadBody {
    const val MAX_EVENTS = 50_000
    const val HEAD_EVENTS = 49_000
    const val TAIL_EVENTS = 1_000
    const val MAX_BYTES = 10 * 1024 * 1024

    private val json = kotlinx.serialization.json.Json

    fun build(runId: String, lines: List<String>, maxBytes: Int = MAX_BYTES): String? {
        var header: JsonObject? = null
        var end: JsonObject? = null
        val events = ArrayList<JsonObject>()
        var lastSeq = Long.MIN_VALUE
        for (line in lines) {
            if (line.isBlank()) continue
            // A crash can cut the last line short: skip anything that isn't one whole object.
            val value = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            when (value.text("type")) {
                "run.start" -> if (header == null) header = strictHeader(value)
                "event" -> strictEvent(value)?.let { event ->
                    // Cosmos needs seq to rise strictly within a run; an out-of-order line is dropped.
                    val seq = event.getValue("seq").jsonPrimitive.content.toLong()
                    if (seq > lastSeq) { events += event; lastSeq = seq }
                }
                "run.end" -> end = strictEnd(value)
            }
        }
        val start = header ?: return null
        if (start.text("runId") != runId) return null
        var head = if (events.size > MAX_EVENTS) HEAD_EVENTS else events.size
        var tail = if (events.size > MAX_EVENTS) TAIL_EVENTS else 0
        while (true) {
            val truncated = head + tail < events.size
            val kept = if (truncated) events.subList(0, head) + events.subList(events.size - tail, events.size) else events
            val body = JsonObject(linkedMapOf<String, JsonElement>(
                "runId" to JsonPrimitive(runId),
                "header" to start,
                "events" to JsonArray(kept),
                "end" to (end ?: JsonNull),
                "truncated" to JsonPrimitive(truncated),
            )).toString()
            if (body.encodeToByteArray().size <= maxBytes || head + tail == 0) return body
            // Still too large: keep shrinking the middle away, always keeping the run's last events.
            when {
                tail == 0 -> { tail = minOf(TAIL_EVENTS, events.size); head = (events.size - tail) / 2 }
                head > 0 -> head /= 2
                else -> tail /= 2
            }
        }
    }

    /*
     * Cosmos's shape rules: strict objects (only the contract's fields), flat `data`/`settings` of at
     * most 50 keys of at most 64 characters, and length limits on the named strings. The writer already
     * keeps to them; these re-check each line, so an older or damaged file is trimmed, never refused.
     */
    private const val MAX_DATA_KEYS = 50
    private const val MAX_KEY_LENGTH = 64
    private const val MAX_VALUE_LENGTH = 200

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.contentOrNull?.toDoubleOrNull() != null }

    private fun limited(value: String?, max: Int): JsonPrimitive? = value?.let { JsonPrimitive(it.take(max)) }

    private fun flat(value: JsonElement?): JsonObject {
        val source = value as? JsonObject ?: return JsonObject(emptyMap())
        val result = linkedMapOf<String, JsonElement>()
        for ((key, item) in source) {
            if (result.size >= MAX_DATA_KEYS) break
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
            val primitive = item as? JsonPrimitive ?: continue
            result[key] = if (primitive.isString) JsonPrimitive(primitive.content.take(MAX_VALUE_LENGTH)) else primitive
        }
        return JsonObject(result)
    }

    private fun strictHeader(value: JsonObject): JsonObject? {
        val runId = value.text("runId") ?: return null
        val at = value.text("at") ?: return null
        return JsonObject(linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("run.start"), "runId" to JsonPrimitive(runId), "at" to JsonPrimitive(at),
            "routineKind" to (limited(value.text("routineKind"), 60) ?: JsonPrimitive("Unknown")),
            "routineName" to (limited(value.text("routineName"), 200) ?: JsonPrimitive("")),
            "appVersion" to (limited(value.text("appVersion"), 100) ?: JsonPrimitive("")),
            "os" to (limited(value.text("os"), 100) ?: JsonPrimitive("")),
            "settings" to flat(value["settings"]),
        ))
    }

    private fun strictEvent(value: JsonObject): JsonObject? {
        val at = value.text("at") ?: return null
        val seq = value.number("seq")?.contentOrNull?.toLongOrNull() ?: return null
        val level = value.text("level")?.takeIf { it in LEVELS } ?: "info"
        val source = value.text("source")?.takeIf { it in SOURCES } ?: "app"
        val event = value.text("event")?.takeIf { it.isNotEmpty() }?.take(100) ?: return null
        return JsonObject(linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("event"), "at" to JsonPrimitive(at), "seq" to JsonPrimitive(seq), "level" to JsonPrimitive(level),
            "source" to JsonPrimitive(source), "event" to JsonPrimitive(event), "data" to flat(value["data"]),
        ))
    }

    private fun strictEnd(value: JsonObject): JsonObject? {
        val at = value.text("at") ?: return null
        return JsonObject(linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("run.end"), "at" to JsonPrimitive(at),
            "outcome" to (limited(value.text("outcome"), 60) ?: JsonPrimitive("Unknown")),
            "events" to (value.number("events") ?: JsonPrimitive(0)),
            "durationMs" to (value.number("durationMs") ?: JsonPrimitive(0)),
        ))
    }

    private val LEVELS = setOf("debug", "info", "warn", "error")
    private val SOURCES = setOf("routine", "page", "video", "extension", "bridge", "answers", "click", "keys", "app", "sync")

    /** Cosmos's answer means the run is stored. */
    val SENT = setOf("stored", "duplicate")
}
