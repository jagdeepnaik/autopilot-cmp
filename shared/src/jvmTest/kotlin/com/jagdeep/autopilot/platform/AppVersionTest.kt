package com.jagdeep.autopilot.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `given the build passes the desktop version when the app reads it then that version is shown`() {
        // The jvmTest task receives the same -Dautopilot.version the launchers get.
        val passed = assertNotNull(System.getProperty(APP_VERSION_PROPERTY), "The build didn't pass $APP_VERSION_PROPERTY")
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(passed), "Unexpected version $passed")
        assertEquals(passed, appVersionName)
    }
}
