package com.jagdeep.shared.answers

import kotlin.test.*

class AnswerBankFilesTest {
    @Test fun `names gain json suffix and imported paths are rejected`() {
        assertEquals("my-bank.json",answerBankFileName(" my-bank "))
        assertEquals("Fleet 2026.json",answerBankFileName("Fleet 2026.json"))
        for (value in listOf("", "../outside.json", "/tmp/out.json", "C:\\bank.json", "a/b.json", "x\n.json")) assertNull(answerBankFileName(value),value)
    }
}
