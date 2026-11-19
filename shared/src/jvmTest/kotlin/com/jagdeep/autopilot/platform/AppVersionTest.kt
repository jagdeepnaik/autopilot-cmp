package com.jagdeep.autopilot.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `given the build passes the desktop version when the app reads it then that version is shown`() {
        // The jvmTest task receives the same compact display version the launchers get.
        val passed = assertNotNull(System.getProperty(APP_VERSION_PROPERTY), "The build didn't pass $APP_VERSION_PROPERTY")
        assertTrue(
            Regex("""\d+\.\d+\.\d+ \(\d+ · [0-9a-fA-F]{7,12}\)""").matches(passed),
            "Unexpected version $passed",
        )
        assertEquals(passed, appVersionName)
    }

    @Test
    fun `given the build passes its technical identity then it remains valid SemVer metadata`() {
        val identity = assertNotNull(
            System.getProperty(APP_BUILD_IDENTITY_PROPERTY),
            "The build didn't pass $APP_BUILD_IDENTITY_PROPERTY",
        )
        assertTrue(
            Regex("""\d+\.\d+\.\d+\+build\.\d+\.g[0-9a-fA-F]{7,12}""").matches(identity),
            "Unexpected technical build identity $identity",
        )
    }
}
