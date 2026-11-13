package com.jagdeep.autopilot

import com.jagdeep.home.domain.CourseAnswer
import com.jagdeep.home.domain.CourseAnswerBank
import com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE
import kotlinx.serialization.json.Json
import kotlin.test.*

class BundledAnswerBankTest {
    @Test fun `default Sagar bank is packaged with answers for every question`() {
        assertTrue(DEFAULT_ANSWER_BANK_FILE in BUNDLED_ANSWER_BANKS)
        val text = assertNotNull(bundledAnswerBank(DEFAULT_ANSWER_BANK_FILE))
        assertNotNull(CourseAnswerBank.decode(text), "The production parser must accept the bundled bank")
        val modules = Json.decodeFromString<Map<String, List<CourseAnswer>>>(text)
        assertEquals(10, modules.size)
        assertEquals(663, modules.values.sumOf { it.size })
        modules.forEach { (module, questions) ->
            questions.forEach { question ->
                assertFalse(question.answer.isNullOrBlank(), "$module: missing saved answer")
                assertTrue(question.answer in question.options, "$module: answer must occur in the displayed options")
            }
        }
    }
}
