package com.jagdeep.home.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CourseAnswerBankFormatTest {
    @Test
    fun `given unnumbered saved answers when website numbers and reorders choices then match by text`() {
        val bank = assertNotNull(CourseAnswerBank.decode("""{"Wellness":[{"question":"Which sound?","options":["Wind","Humming of a bee"],"answer":"Humming of a bee"}]}"""))
        val question = CourseQuestion("q", "Which sound?", listOf(
            CourseOption("bee", "1. Humming of a bee"), CourseOption("wind", "2. Wind")))
        assertEquals("bee", bank.answer("Wellness", question)?.id)
        assertNull(bank.answer("Wellness", question.copy(options = listOf(
            CourseOption("bee", "1. Humming of a bee"), CourseOption("other", "2. OM")))))
    }

    @Test
    fun `given duplicate correct choices when loading then retain valid answers and leave ambiguous question unanswered`() {
        val bank = assertNotNull(CourseAnswerBank.decode("""{"Wellness":[
            {"question":"Ambiguous?","options":["1. Upgrade","2. Personal action","3. Shared action","4. Upgrade"],"answer":"Upgrade"},
            {"question":"Clear?","options":["Yes","No"],"answer":"Yes"}
        ]}"""))
        assertNull(bank.module("Wellness")!!.first().answer)
        assertNull(bank.answer("Wellness", CourseQuestion("a", "Ambiguous?", listOf(
            CourseOption("a", "1. Upgrade"), CourseOption("b", "2. Personal action"),
            CourseOption("c", "3. Shared action"), CourseOption("d", "4. Upgrade")))))
        assertEquals("yes", bank.answer("Wellness", CourseQuestion("c", "Clear?", listOf(
            CourseOption("yes", "Yes"), CourseOption("no", "No"))))?.id)
    }

    @Test
    fun `given an answer missing from choices when loading then reject the bank`() {
        assertNull(CourseAnswerBank.decode("""{"Wellness":[{"question":"Which?","options":["First","Second"],"answer":"Third"}]}"""))
    }

    @Test
    fun `given decimal options when matching then preserve their numeric meaning`() {
        val bank = assertNotNull(CourseAnswerBank.decode("""{"Wellness":[{"question":"How much?","options":["2.1 to 4.2 liters","4.5 to 6.0 liters"],"answer":"2.1 to 4.2 liters"}]}"""))
        assertEquals("low", bank.answer("Wellness", CourseQuestion("q", "How much?", listOf(
            CourseOption("high", "1. 4.5 to 6.0 liters"), CourseOption("low", "2. 2.1 to 4.2 liters"))))?.id)
    }
}
