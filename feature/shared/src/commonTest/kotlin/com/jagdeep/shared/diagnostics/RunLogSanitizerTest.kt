package com.jagdeep.shared.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLogSanitizerTest {
    private val sanitizer = RunLogSanitizer(personal = listOf("jagdeepuser", "/Users/jagdeepuser"))

    @Test fun given_a_url_in_a_string_when_sanitised_then_the_address_is_replaced() {
        assertEquals("Opened [url] then [url]", sanitizer.text("Opened https://sagarmeinyog.com/dashboard/courses/abc?x=1 then www.example.com/a"))
        assertEquals("ext [url]", sanitizer.text("ext chrome-extension://abc/page.html"))
    }

    @Test fun given_an_email_or_token_when_sanitised_then_they_are_replaced() {
        assertEquals("sent by [email]", sanitizer.text("sent by someone.name@example.co.in"))
        assertEquals("Bearer [token]", sanitizer.text("Bearer abc.def-123"))
        assertEquals("jwt [token]", sanitizer.text("jwt eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl"))
    }

    @Test fun given_user_folders_and_names_when_sanitised_then_they_are_replaced() {
        assertEquals("at /Users/[user]/x and /home/[user]/y and C:\\Users\\[user]\\z", sanitizer.text("at /Users/other/x and /home/bob/y and C:\\Users\\alice\\z"))
        assertEquals("hello [user]", sanitizer.text("hello JagdeepUser"))
    }

    @Test fun given_long_or_multiline_text_when_sanitised_then_it_is_one_short_line() {
        val text = sanitizer.text("line one\nline two\t" + "x".repeat(500))
        assertFalse(text.contains('\n'))
        assertEquals(RunLogSanitizer.MAX_STRING, text.length)
        assertTrue(text.startsWith("line one line two"))
    }

    @Test fun given_text_under_forbidden_keys_when_sanitised_then_it_is_dropped() {
        val data = sanitizer.data(mapOf(
            "url" to "https://a.b", "pageTitle" to "Welcome", "questionText" to "What?", "options" to "A|B", "answer" to "B",
            "typedValue" to "hunter2", "password" to "x", "email" to "a@b.cd", "cookie" to "c", "token" to "t",
            "userName" to "Jag", "accountName" to "Jag", "profileName" to "Jag", "path" to "/dashboard", "module" to "Module 3",
            "courseName" to "Course", "chapterTitle" to "Chapter",
        ))
        assertTrue(data.isEmpty(), data.toString())
    }

    @Test fun given_safe_suffixes_numbers_and_flags_when_sanitised_then_they_are_kept() {
        val data = sanitizer.data(mapOf(
            "questionKey" to "8c9f", "pathHash" to "-123", "textLength" to 12, "moduleIndex" to 3,
            "answerAssessments" to true, "answerCount" to 2, "state" to "paused", "currentTime" to 12.5, "errorCode" to null,
        ))
        assertEquals(mapOf<String, Any?>("questionKey" to "8c9f", "pathHash" to "-123", "textLength" to 12, "moduleIndex" to 3,
            "answerAssessments" to true, "answerCount" to 2, "state" to "paused", "currentTime" to 12.5, "errorCode" to null), data)
    }

    @Test fun given_a_label_when_sanitised_then_it_is_at_most_forty_characters() {
        val data = sanitizer.data(mapOf("label" to "Start the assessment for this module now please", "targetLabel" to "y".repeat(60)))
        assertEquals(RunLogSanitizer.MAX_LABEL, (data["label"] as String).length)
        assertEquals(RunLogSanitizer.MAX_LABEL, (data["targetLabel"] as String).length)
    }

    @Test fun given_unsupported_values_when_sanitised_then_they_become_safe_primitives() {
        val data = sanitizer.data(mapOf("nan" to Double.NaN, "list" to listOf("https://x.y/z"), "bad key!" to 1))
        assertNull(data["nan"])
        assertEquals("[[url]", data["list"])
        assertFalse("bad key!" in data)
    }

    @Test fun given_an_error_when_summarised_then_only_its_class_and_first_line_remain() {
        val summary = RunLogSanitizer.errorData(IllegalStateException("Broken at /Users/bob/file\n\tat stack.Frame"))
        assertEquals("IllegalStateException", summary["errorClass"])
        assertEquals("Broken at /Users/[user]/file", sanitizer.data(summary)["message"])
    }
}
