package com.jagdeep.home.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertNull(session.action)
        session.captureSaved(true)
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

    private fun page(document: String = "page", ready: Boolean = true) = MarineSamrajReading(
        session = "session", document = document, url = "https://marinesamraj.com/exam-prep/question/one", ready = ready,
        question = "What is a public question?", answer = "A published reference answer.",
        links = listOf("https://marinesamraj.com/exam-prep/question/two"))

    @Test fun given_loading_page_when_read_then_no_capture_or_completion_occurs() {
        val runner = MarineSamrajSession("session", 10)
        runner.read(page(ready = false))
        assertNull(runner.takeCapture()); assertNull(runner.action)
        assertFalse(runner.progress.completed); assertEquals(0, runner.progress.pages)
    }

    @Test fun given_save_failure_when_confirmed_then_collection_pauses_without_navigation() {
        val runner = MarineSamrajSession("session", 10)
        runner.read(page()); assertNotNull(runner.takeCapture()); runner.captureSaved(false)
        runner.read(page("later"))
        assertNull(runner.action); assertEquals(0, runner.progress.questions)
        assertFalse(runner.progress.completed); assertNull(runner.checkpoint())
    }

    @Test fun given_short_public_answer_when_saved_then_it_is_not_discarded() {
        val runner = MarineSamrajSession("session", 1)
        runner.read(page().copy(answer = "Yes.")); assertEquals("Yes.", runner.takeCapture()?.answer)
        runner.captureSaved(true)
        assertEquals(1, runner.progress.questions); assertTrue(runner.progress.completed)
    }

    @Test fun given_acknowledged_navigation_when_old_document_repeats_then_wait_for_destination() {
        val runner = MarineSamrajSession("session", 10)
        runner.read(page()); runner.takeCapture(); runner.captureSaved(true)
        val action = assertNotNull(runner.action)
        runner.acknowledge(action.request, true); runner.read(page())
        assertNull(runner.action); assertEquals(1, runner.progress.questions)
    }

    @Test fun given_nonlink_question_control_when_ready_then_it_is_selected_once() {
        val runner = MarineSamrajSession("session", 10)
        runner.read(page().copy(question = null, answer = null, links = emptyList(), controls = listOf(MarineSamrajControl("row", "question-key"))))
        assertEquals("row", runner.action?.control)
    }

    @Test fun given_checkpoint_when_restarted_elsewhere_then_resume_original_page_first() {
        val runner = MarineSamrajSession("session", 10)
        runner.restore(MarineSamrajCheckpoint(listOf(page().url), page().links, page().url))
        runner.read(page().copy(url = "https://marinesamraj.com/EP_Home", question = null, answer = null))
        assertEquals(page().url, runner.action?.url)
        assertEquals(page().url, runner.checkpoint()?.resumeUrl)
    }

    @Test fun given_restored_pages_when_new_run_starts_then_page_limit_is_per_run() {
        val runner = MarineSamrajSession("session", 2)
        runner.restore(MarineSamrajCheckpoint(listOf("https://marinesamraj.com/exam-prep/question/old")))
        runner.read(page()); runner.takeCapture(); runner.captureSaved(true)
        assertFalse(runner.progress.completed); assertNotNull(runner.action)
    }

    @Test fun given_duplicate_question_when_page_repeats_then_it_is_saved_once() {
        val runner = MarineSamrajSession("session", 10)
        runner.read(page().copy(links = emptyList())); runner.takeCapture(); runner.captureSaved(true)
        runner.read(page("duplicate")); assertNull(runner.takeCapture()); assertEquals(1, runner.progress.questions)
    }

    @Test fun given_untrusted_or_private_urls_when_canonicalizing_then_they_are_rejected() {
        listOf("https://evil.test/exam-prep/question/one", "https://marinesamraj.com/user/profile?next=/exam-prep/", "https://marinesamraj.com/ep_home-evil", "https://marinesamraj.com/exam-prep/%2fadmin", "https://marinesamraj.com/exam-prep/subject/one?tabID=BOOKMARKS").forEach { assertNull(marineSamrajCanonicalUrl(it), it) }
    }

    @Test fun given_tracking_variants_when_canonicalizing_then_content_identity_stays_stable() {
        assertEquals(marineSamrajCanonicalUrl("https://marinesamraj.com/exam-prep/question/one"), marineSamrajCanonicalUrl("https://www.marinesamraj.com/exam-prep/question/one?navigationData=ignored&tabID=ANSWER#top"))
        assertNotNull(marineSamrajCanonicalUrl("https://marinesamraj.com/sitemap.xml"))
    }

    @Test fun given_public_alias_redirect_when_destination_arrives_then_alias_is_not_queued_again() {
        val runner = MarineSamrajSession("session", 20)
        val home = page().copy(url = "https://marinesamraj.com/EP_Home?examLevelID=one", question = null, answer = null,
            links = listOf("https://marinesamraj.com/EP_Home", "https://marinesamraj.com/exam-prep/subject/next"))
        runner.read(home)
        val first = assertNotNull(runner.action)
        assertEquals("https://marinesamraj.com/EP_Home", first.url)
        runner.acknowledge(first.request, true)
        runner.read(home.copy(document = "redirected"))
        assertEquals("https://marinesamraj.com/exam-prep/subject/next", runner.action?.url)
    }
}
