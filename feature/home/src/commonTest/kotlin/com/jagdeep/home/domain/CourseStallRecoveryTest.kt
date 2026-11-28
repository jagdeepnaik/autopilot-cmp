package com.jagdeep.home.domain

import com.jagdeep.home.domain.CourseStallRecovery.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CourseStallRecoveryTest {
    private val recovery = CourseStallRecovery(stallMillis = 90_000, reloadTimeoutMillis = 60_000, maxReloads = 2)

    @Test fun `given no progress outside a wait then reload once and say so`() {
        assertEquals(Step.None, recovery.check(0, blocked = false))
        assertEquals(Step.None, recovery.check(89_999, blocked = false))
        val reload = assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        assertEquals(90_000, reload.stalledMillis)
        assertEquals(reload.request, recovery.pendingRequest)
        assertEquals(Step.None, recovery.check(100_000, blocked = false), "Only one request per stall")
    }

    @Test fun `waits for the user or a timer never count as a stall`() {
        recovery.check(0, blocked = false)
        assertEquals(Step.None, recovery.check(500_000, blocked = true))
        assertEquals(Step.None, recovery.check(589_999, blocked = false), "The stall clock restarts when the wait ends")
        assertIs<Step.Reload>(recovery.check(590_000, blocked = false))
    }

    @Test fun `given the reloaded page reports and then moves then the run has recovered`() {
        recovery.check(0, blocked = false)
        val reload = assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        assertEquals(Step.None, recovery.progressed(90_500), "The old page, before the reload")
        assertEquals(Step.None, recovery.acknowledged(reload.request, success = true, problem = null, now = 91_000))
        assertEquals(0, recovery.pendingRequest)
        assertEquals(Step.PageFound(2_000), recovery.progressed(93_000))
        assertEquals(Step.Recovered(5_000), recovery.progressed(96_000))
        assertIs<Step.Reload>(recovery.check(186_000, blocked = false), "A later, separate stall may reload again")
    }

    @Test fun `given the reloaded page still doesn't move then give up and leave it to the alert`() {
        recovery.check(0, blocked = false)
        val reload = assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        recovery.acknowledged(reload.request, success = true, problem = null, now = 91_000)
        recovery.progressed(92_000)
        assertEquals(Step.GaveUp("stillStalled"), recovery.check(151_000, blocked = false))
        assertEquals(Step.None, recovery.check(400_000, blocked = false), "No second reload for the same stall")
        assertEquals(1, recovery.reloads)
    }

    @Test fun `given the extension can't reload then give up with its reason`() {
        recovery.check(0, blocked = false)
        val reload = assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        assertEquals(Step.None, recovery.acknowledged(reload.request + 1, success = false, problem = "noTab", now = 90_100), "Another request's reply")
        assertEquals(Step.GaveUp("reloadRefused:inactiveTab"), recovery.acknowledged(reload.request, success = false, problem = "inactiveTab", now = 90_100))
        assertEquals(0, recovery.pendingRequest)
    }

    @Test fun `given no reply to a reload then stop asking`() {
        recovery.check(0, blocked = false)
        assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        assertEquals(Step.GaveUp("noReloadReply"), recovery.check(150_000, blocked = false))
        assertEquals(0, recovery.pendingRequest)
    }

    @Test fun `given a reloaded page that waits for the user then the run has recovered`() {
        recovery.check(0, blocked = false)
        val reload = assertIs<Step.Reload>(recovery.check(90_000, blocked = false))
        recovery.acknowledged(reload.request, success = true, problem = null, now = 91_000)
        recovery.progressed(92_000)
        assertEquals(Step.Recovered(9_000), recovery.check(100_000, blocked = true))
    }

    @Test fun `a run reloads at most its limit and requests always increase`() {
        var now = 0L
        recovery.check(now, blocked = false)
        val requests = mutableListOf<Long>()
        repeat(2) {
            now += 100_000
            val reload = assertIs<Step.Reload>(recovery.check(now, blocked = false))
            requests += reload.request
            recovery.acknowledged(reload.request, success = true, problem = null, now = now)
            recovery.progressed(now + 1); recovery.progressed(now + 2) // Found, then moving.
        }
        assertEquals(requests.sorted().distinct(), requests)
        assertEquals(Step.GaveUp("reloadLimit"), recovery.check(now + 90_002, blocked = false))
    }
}
