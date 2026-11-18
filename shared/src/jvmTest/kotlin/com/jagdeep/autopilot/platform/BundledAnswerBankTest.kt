package com.jagdeep.autopilot.platform

import kotlin.test.*

class BundledAnswerBankTest {
    @Test fun `routine Q and A databases are packaged`() {
        listOf("smy_qa.db", "olp_qa.db", "seagull_qa.db", "marinesamraj_qa.db").forEach { name ->
            val header = assertNotNull(javaClass.getResourceAsStream("/qa-routines/$name"), "Missing $name").use {
                ByteArray(16).also(it::read)
            }.decodeToString()
            assertTrue(header.startsWith("SQLite format 3"), "$name is not a SQLite database")
        }
    }
}
