package com.jagdeep.automation

import kotlin.test.*

class DisplayAwakeTest {
    @Test fun `windows hold acquires once and releases on its owning thread`() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val hold = ThreadDisplayAwake { calls.add(it to Thread.currentThread().id); true }
        hold.setAwake(true)
        hold.setAwake(true)
        hold.setAwake(false)
        hold.setAwake(false)
        assertEquals(listOf(ThreadDisplayAwake.DISPLAY_AND_SYSTEM, ThreadDisplayAwake.RELEASE), calls.map { it.first })
        assertEquals(1, calls.map { it.second }.distinct().size)
        assertNotEquals(Thread.currentThread().id, calls.first().second)
    }
    @Test fun `failed hold can be retried`() {
        var allowed = false
        val hold = ThreadDisplayAwake { it == ThreadDisplayAwake.RELEASE || allowed }
        assertFailsWith<IllegalStateException> { hold.setAwake(true) }
        allowed = true
        hold.setAwake(true)
        hold.setAwake(false)
    }
    @Test fun `linux fallback retains connection until release and closes once`() {
        val attempted = mutableListOf<IdleService>()
        var closes = 0
        var released: Pair<IdleService, Int>? = null
        val session = object : IdleSession {
            override fun inhibit(service: IdleService): Int? { attempted += service; return if (service == idleServices[1]) 42 else null }
            override fun release(service: IdleService, cookie: Int) { released = service to cookie }
            override fun close() { closes++ }
        }
        val lease = acquireLinuxDisplayInhibition { session }
        assertEquals(idleServices.take(2), attempted)
        assertEquals(0, closes)
        lease.close(); lease.close()
        assertEquals(idleServices[1] to 42, released)
        assertEquals(1, closes)
    }
    @Test fun `unavailable linux service closes connection and reports failure`() {
        var closes = 0
        assertFailsWith<IllegalStateException> {
            acquireLinuxDisplayInhibition { object : IdleSession {
                override fun inhibit(service: IdleService): Int? = null
                override fun release(service: IdleService, cookie: Int) = error("No cookie")
                override fun close() { closes++ }
            } }
        }
        assertEquals(1, closes)
    }
}
