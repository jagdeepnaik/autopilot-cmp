package com.jagdeep.home.domain

import com.jagdeep.persistence.qa.QuestionAnswerText
import kotlin.test.Test
import kotlin.test.assertEquals

class RunLogQuestionKeyTest {
    @Test fun given_a_question_when_its_log_key_is_made_then_it_equals_the_routine_file_question_key() {
        val cases = listOf(
            "What is the minimum  number?" to listOf("2. Three", "1. Two"),
            "Ship safety — ‘quoted’?" to listOf("A", "b", "C d"),
            "Unicode: नमस्ते" to listOf("हाँ", "नहीं"),
        )
        cases.forEach { (question, options) ->
            assertEquals(QuestionAnswerText.questionKey(question, options), runLogQuestionKey(question, options))
        }
    }
}
