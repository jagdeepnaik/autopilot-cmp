package com.jagdeep.home.domain

import kotlin.test.*

class RoutineInactivityMonitorTest {
    @Test fun `no progress alerts at five minutes and every five minutes thereafter`() {
        val monitor = RoutineInactivityMonitor()
        assertFalse(monitor.observe(true, "page", 0, true))
        assertFalse(monitor.observe(true, "page", 299999, true))
        assertTrue(monitor.observe(true, "page", 300000, true))
        assertTrue(monitor.isStalled)
        assertFalse(monitor.observe(true, "page", 599999, true))
        assertTrue(monitor.observe(true, "page", 600000, true))
        assertFalse(monitor.observe(true, "next", 600001, true))
        assertFalse(monitor.isStalled)
        assertFalse(monitor.observe(true, "next", 900000, true))
        assertTrue(monitor.observe(true, "next", 900001, true))
    }
    @Test fun `muted alerts remain silent and stopping clears the previous run`() {
        val monitor = RoutineInactivityMonitor()
        monitor.observe(true, "page", 0, false)
        assertFalse(monitor.observe(true, "page", 600000, false))
        assertTrue(monitor.isStalled)
        assertTrue(monitor.observe(true, "page", 600001, true))
        assertFalse(monitor.observe(false, "page", 600002, true))
        assertFalse(monitor.isStalled)
        assertFalse(monitor.observe(true, "page", 900002, true))
        assertFalse(monitor.observe(true, "page", 1200001, true))
        assertTrue(monitor.observe(true, "page", 1200002, true))
    }
    @Test fun `video position advances count as progress but unchanged playing flags do not`() {
        val monitor = RoutineInactivityMonitor()
        fun video(time: Double) = CourseSnapshot("s", "doc", "/dashboard/courses/c/l", elements = listOf(
            CourseElement("v", tag="video", paused=false, mediaPosition=time)))
        monitor.observe(true, video(0.0).progressMarker(), 0, true)
        for (minute in 1..10) assertFalse(monitor.observe(true, video(minute * 60.0).progressMarker(), minute * 60000L, true))
        assertTrue(monitor.observe(true, video(600.0).progressMarker(), 900000, true))
    }
    @Test fun `recreated progress controls are not progress but a new table page is`() {
        val page = CourseSnapshot("s", "doc", "/dashboard/progress", progress = ProgressTable(page=1,pages=2,next="old-control"))
        assertEquals(page.progressMarker(),page.copy(progress=page.progress!!.copy(next="new-control")).progressMarker())
        assertNotEquals(page.progressMarker(),page.copy(progress=page.progress!!.copy(page=2)).progressMarker())
    }
    @Test fun `unrelated countdown labels and recreated controls are not progress`() {
        val base = CourseSnapshot("s", "doc", "/dashboard/courses/c/l", elements=listOf(
            CourseElement("timer1", tag="h2", label="Time left 10:00"), CourseElement("v1",tag="video",mediaPosition=20.0)))
        val tick = base.copy(elements=listOf(CourseElement("timer2",tag="h2",label="Time left 09:59"),CourseElement("v2",tag="video",mediaPosition=20.0)))
        assertEquals(base.progressMarker(), tick.progressMarker())
        assertNotEquals(base.progressMarker(), base.copy(path="/dashboard/courses/c/next").progressMarker())
    }
}
