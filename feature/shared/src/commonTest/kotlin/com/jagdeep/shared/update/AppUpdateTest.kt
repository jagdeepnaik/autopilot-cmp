package com.jagdeep.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateTest {
    private val url = "https://cosmos.naikio.in/downloads"

    @Test fun given_a_newer_build_when_compared_then_it_is_offered() {
        assertEquals(AppUpdate("1.1.1", 171, url), newerUpdate(170, LatestBuild("1.1.1", 171, url)))
    }

    @Test fun given_the_same_or_an_older_build_when_compared_then_nothing_is_offered() {
        assertNull(newerUpdate(170, LatestBuild("1.1.0", 170, url)))
        assertNull(newerUpdate(170, LatestBuild("1.0.9", 160, url)))
    }

    @Test fun given_nothing_published_or_a_missing_build_number_when_compared_then_nothing_is_offered() {
        assertNull(newerUpdate(170, null))
        assertNull(newerUpdate(170, LatestBuild("1.1.1", null, url)))
        assertNull(newerUpdate(null, LatestBuild("1.1.1", 171, url)))
        assertNull(newerUpdate(170, LatestBuild("1.1.1", 171, null)))
        assertNull(newerUpdate(170, LatestBuild("1.1.1", 171, "javascript:alert(1)")))
    }

    @Test fun given_a_build_identity_when_read_then_its_build_number_is_found() {
        assertEquals(170, buildNumberOf("1.1.0+build.170.gfadd6e3"))
        assertNull(buildNumberOf("1.1.0"))
    }

    @Test fun given_checks_when_the_server_fails_or_answers_then_failures_show_nothing_and_repeats_wait() = runTest {
        var calls = 0
        var clock = 0L
        var answer: LatestBuild? = null
        val checker = UpdateChecker(CoroutineScope(StandardTestDispatcher(testScheduler)), 170, { clock }) { calls++; answer ?: error("offline") }
        checker.check(); advanceUntilIdle()
        assertNull(checker.update.value)
        checker.check(); advanceUntilIdle()
        assertEquals(1, calls)
        clock += 10 * 60_000
        answer = LatestBuild("1.1.1", 171, url)
        checker.check(); advanceUntilIdle()
        assertEquals(171, checker.update.value?.build)
    }
}
