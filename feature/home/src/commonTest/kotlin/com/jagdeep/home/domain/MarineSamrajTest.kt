package com.jagdeep.home.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarineSamrajTest {
    @Test
    fun given_public_question_when_reading_then_it_is_captured_and_next_public_link_is_opened() {
        val session = MarineSamrajSession("session", 3)
        session.read(MarineSamrajReading(
            session = "session", document = "page", url = "https://marinesamraj.com/exam-prep/question/one",
            question = "What is the correct public answer?", answer = "This is the public answer.",
            links = listOf("https://marinesamraj.com/exam-prep/question/two"),
        ))
        assertEquals("What is the correct public answer?", assertNotNull(session.takeCapture()).question)
        assertEquals("https://marinesamraj.com/exam-prep/question/two", assertNotNull(session.action).url)
    }

    @Test
    fun given_private_link_when_reading_then_it_is_never_queued() {
        val session = MarineSamrajSession("session", 3)
        session.read(MarineSamrajReading(
            session = "session", document = "page", url = "https://marinesamraj.com/exam-prep/question/one",
            links = listOf("https://marinesamraj.com/admin/questions", "https://marinesamraj.com/user/profile"),
        ))
        assertTrue(session.progress.completed)
        assertFalse(session.progress.detail.contains("Opening"))
    }
}
