package com.jagdeep.home.domain.compass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class RunLogUploadBodyTest {
    private fun header(id: String = "r1") = """{"type":"run.start","runId":"$id","at":"2026-11-28T10:00:00.000Z","routineKind":"Click","routineName":"R","appVersion":"v","os":"o","settings":{}}"""
    private fun event(seq: Int) = """{"type":"event","at":"2026-11-28T10:00:01.000Z","seq":$seq,"level":"info","source":"click","event":"click","data":{}}"""
    private val end = """{"type":"run.end","at":"2026-11-28T10:00:02.000Z","outcome":"Completed","events":2,"durationMs":2000}"""

    @Test fun given_a_finished_run_when_built_then_the_body_wraps_header_events_and_end() {
        val body = Json.parseToJsonElement(RunLogUploadBody.build("r1", listOf(header(), event(1), event(2), end))!!).jsonObject
        assertEquals(setOf("runId", "header", "events", "end", "truncated"), body.keys)
        assertEquals("r1", body.getValue("runId").jsonPrimitive.content)
        assertEquals("run.start", body.getValue("header").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf(1L, 2L), body.getValue("events").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.long })
        assertEquals("Completed", body.getValue("end").jsonObject.getValue("outcome").jsonPrimitive.content)
        assertEquals(false, body.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test fun given_a_run_cut_short_by_a_crash_when_built_then_end_is_null_and_the_broken_line_is_skipped() {
        val body = Json.parseToJsonElement(RunLogUploadBody.build("r1", listOf(header(), event(1), """{"type":"event","at":"2026-11"""))!!).jsonObject
        assertEquals(JsonNull, body.getValue("end"))
        assertEquals(1, body.getValue("events").jsonArray.size)
    }

    @Test fun given_no_header_or_another_run_id_when_built_then_there_is_nothing_to_upload() {
        assertNull(RunLogUploadBody.build("r1", listOf(event(1), end)))
        assertNull(RunLogUploadBody.build("r1", listOf(header("other"), end)))
    }

    @Test fun given_more_than_fifty_thousand_events_when_built_then_the_first_49000_and_last_1000_are_kept() {
        val lines = listOf(header()) + (1..50_500).map(::event) + end
        val body = Json.parseToJsonElement(RunLogUploadBody.build("r1", lines)!!).jsonObject
        val seqs = body.getValue("events").jsonArray.map { it.jsonObject.getValue("seq").jsonPrimitive.long }
        assertEquals(50_000, seqs.size)
        assertEquals(49_000L, seqs[48_999])
        assertEquals(49_501L, seqs[49_000])
        assertEquals(50_500L, seqs.last())
        assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test fun given_a_body_over_the_size_limit_when_built_then_it_shrinks_and_keeps_the_last_events() {
        val lines = listOf(header()) + (1..2_000).map(::event) + end
        val built = RunLogUploadBody.build("r1", lines, maxBytes = 60_000)!!
        assertTrue(built.encodeToByteArray().size <= 60_000)
        val body = Json.parseToJsonElement(built).jsonObject
        assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(2_000L, body.getValue("events").jsonArray.last().jsonObject.getValue("seq").jsonPrimitive.long)
    }

    @Test fun given_compass_statuses_then_stored_and_duplicate_mean_sent() {
        assertEquals(setOf("stored", "duplicate"), RunLogUploadBody.SENT)
    }

    @Test fun given_lines_outside_the_shape_rules_when_built_then_they_are_trimmed_to_the_contract() {
        val longKey = "k".repeat(70)
        val header = """{"type":"run.start","runId":"r1","at":"2026-11-28T10:00:00.000Z","routineKind":"${"K".repeat(80)}","routineName":"${"n".repeat(300)}","appVersion":"${"v".repeat(150)}","os":"o","settings":{"a":1,"nested":{"x":1},"$longKey":2},"extra":"x"}"""
        val data = (1..60).joinToString(",") { "\"k$it\":$it" }
        val lines = listOf(header,
            """{"type":"event","at":"t","seq":2,"level":"info","source":"video","event":"${"e".repeat(150)}","data":{$data},"unknown":1}""",
            """{"type":"event","at":"t","seq":2,"level":"info","source":"video","event":"dup","data":{}}""",
            """{"type":"event","at":"t","seq":1,"level":"loud","source":"elsewhere","event":"old","data":{}}""",
            """{"type":"event","at":"t","seq":3,"level":"loud","source":"elsewhere","event":"next","data":{"list":[1]}}""",
            """{"type":"run.end","at":"t","outcome":"${"o".repeat(80)}","events":3,"durationMs":5,"more":true}""")
        val body = Json.parseToJsonElement(RunLogUploadBody.build("r1", lines)!!).jsonObject
        val head = body.getValue("header").jsonObject
        assertEquals(setOf("type", "runId", "at", "routineKind", "routineName", "appVersion", "os", "settings"), head.keys)
        assertEquals(60, head.getValue("routineKind").jsonPrimitive.content.length)
        assertEquals(200, head.getValue("routineName").jsonPrimitive.content.length)
        assertEquals(100, head.getValue("appVersion").jsonPrimitive.content.length)
        assertEquals(setOf("a"), head.getValue("settings").jsonObject.keys)
        val events = body.getValue("events").jsonArray.map { it.jsonObject }
        assertEquals(listOf(2L, 3L), events.map { it.getValue("seq").jsonPrimitive.long })
        assertEquals(setOf("type", "at", "seq", "level", "source", "event", "data"), events[0].keys)
        assertEquals(100, events[0].getValue("event").jsonPrimitive.content.length)
        assertEquals(50, events[0].getValue("data").jsonObject.size)
        assertEquals("info", events[1].getValue("level").jsonPrimitive.content)
        assertEquals("app", events[1].getValue("source").jsonPrimitive.content)
        assertTrue(events[1].getValue("data").jsonObject.isEmpty())
        val end = body.getValue("end").jsonObject
        assertEquals(setOf("type", "at", "outcome", "events", "durationMs"), end.keys)
        assertEquals(60, end.getValue("outcome").jsonPrimitive.content.length)
    }
}
