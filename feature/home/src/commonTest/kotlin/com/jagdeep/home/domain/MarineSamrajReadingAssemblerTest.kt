package com.jagdeep.home.domain

import kotlinx.serialization.json.Json
import kotlin.test.*

class MarineSamrajReadingAssemblerTest {
    private val reading = MarineSamrajReading(session = "session", document = "doc", url = "https://marinesamraj.com/exam-prep/question/one", question = "A public question", answer = "Long public answer ".repeat(2000))
    private fun chunks(): List<CourseWireEvent> {
        val parts = Json.encodeToString(MarineSamrajReading.serializer(), reading).chunked(1000)
        return parts.mapIndexed { index, part -> CourseWireEvent("marineSamrajChunk", "session", "snapshot", index, parts.size, part) }
    }
    @Test fun given_long_answer_when_all_chunks_arrive_then_full_text_is_preserved() {
        val assembler = MarineSamrajReadingAssembler()
        val events = chunks()
        events.dropLast(1).forEach { assertNull(assembler.accept(it)) }
        assertEquals(reading, assembler.accept(events.last()))
    }
    @Test fun given_out_of_order_or_mixed_chunks_when_read_then_no_reading_is_emitted() {
        val assembler = MarineSamrajReadingAssembler(); val events = chunks()
        assertNull(assembler.accept(events[0])); assertNull(assembler.accept(events[2]))
        events.drop(1).forEach { assertNull(assembler.accept(it)) }
        assertNull(assembler.accept(events[0])); assertNull(assembler.accept(events[1].copy(session = "other")))
    }
    @Test fun given_oversized_chunk_when_read_then_it_is_rejected() {
        assertNull(MarineSamrajReadingAssembler().accept(CourseWireEvent("marineSamrajChunk", "session", "snapshot", 0, 1, "x".repeat(1001))))
    }
}
