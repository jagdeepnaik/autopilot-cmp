package com.jagdeep.home.domain

import com.jagdeep.persistence.room.learning.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CourseProgressTest {
    @Test fun `closed page size menu is reopened and maximum is checked before saving`() = runTest {
        val store = Store(); val runner = CourseProgressSession(store,"run",5000)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        val table = ProgressTable(true,page=1,pages=1,total=1,rows=listOf(row()),unfiltered=true,pageSize=100,pageSizeControl="size")
        fun page(value: ProgressTable) = snapshot(value).copy(elements=snapshot(value).elements + listOf(CourseElement("size",label="100"),CourseElement("max",label="100")))
        runner.process(page(table),1000)
        val first = runner.action!!.request
        runner.acknowledge(first,true,1000)
        runner.process(page(table),5999)
        assertNull(runner.action,"Respect the configured wait before reopening")
        runner.process(page(table),6000)
        assertEquals("size",runner.action?.element)
        assertTrue(runner.action!!.request > first)
        assertEquals(0,store.writes)
        runner.acknowledge(runner.action!!.request,true,6000)
        runner.process(page(table.copy(pageSizeOpen=true,pageSizeOptions=listOf(ProgressPageSize("max",100)))),11000)
        assertEquals("max",runner.action?.element)
        runner.acknowledge(runner.action!!.request,true,11000)
        runner.process(page(table),16000)
        assertEquals(1,store.writes)
    }

    @Test fun `menu that never opens pauses after bounded retries without saving partial history`() = runTest {
        val store = Store(); val runner = CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        val table = ProgressTable(true,page=1,pages=1,total=1,rows=listOf(row()),unfiltered=true,pageSize=100,pageSizeControl="size")
        val page = snapshot(table).copy(elements=snapshot(table).elements+CourseElement("size",label="100"))
        repeat(3) { index ->
            runner.process(page,(index+1)*1000L)
            assertEquals("size",runner.action?.element)
            runner.acknowledge(runner.action!!.request,true,(index+1)*1000L)
        }
        runner.process(page,4000)
        assertNull(runner.action)
        assertTrue(runner.blocksActions)
        assertTrue(runner.detail.contains("three tries"))
        assertEquals(0,store.writes)
    }

    private class Store : CourseProgressRepository {
        override val available = true
        val saved = mutableMapOf<String, LearningRecord>()
        var fails = false
        var writes = 0
        var profiles = emptyList<LearningAccount>()
        override fun accounts() = flowOf(profiles)
        override suspend fun records(account: String) = saved.values.filter { it.account == account }
        override suspend fun save(account: LearningAccount, records: List<LearningRecord>) { if(fails) error("test write failed"); writes++; records.forEach { saved[it.account + it.key] = it } }
    }
    private fun row(status: String = "YES", attempt: String = "1") = mapOf("Course Name" to "Wellness", "Test Title" to "Course Assessment", "Test Type" to "ASSESSMENT", "Attempt Number" to attempt, "Started At" to "09/11/2026, 00:01", "Completed At" to "09/11/2026, 00:10", "Passed" to status, "Percentage" to if(status == "NOT SUBMITTED") "—" else "90%")
    private fun snapshot(table: ProgressTable? = null, name: String = "Person") = CourseSnapshot("run","doc",if(table == null) "/dashboard" else "/dashboard/progress", profileName = name, progress = table,
        elements = listOf(CourseElement("progress",tag="a",href="/dashboard/progress"),CourseElement("home",tag="a",href="/dashboard"),CourseElement("next"),CourseElement("first")))
    @Test fun `given page size options then select the maximum before reading and verify it loaded`() = runTest {
        val store=Store(); val runner=CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        val table=ProgressTable(true,page=6,pages=14,total=1,rows=listOf(row()),unfiltered=true,pageSize=10,pageSizeControl="size")
        fun sized(value: ProgressTable)=snapshot(value).copy(elements=snapshot(value).elements+listOf(
            CourseElement("size",label=value.pageSize.toString()),CourseElement("small",label="50"),CourseElement("maximum",label="100"),CourseElement("disabled",label="200",enabled=false)))
        runner.process(sized(table),1000)
        assertEquals("size",runner.action?.element); assertEquals("progressPageSize",runner.action?.kind)
        assertEquals(0,store.writes)
        runner.acknowledge(runner.action!!.request,true,1000)
        runner.process(sized(table.copy(pageSizeOpen=true,pageSizeOptions=listOf(ProgressPageSize("small",50),ProgressPageSize("maximum",100),ProgressPageSize("disabled",200)))),2000)
        assertEquals("maximum",runner.action?.element); assertEquals("100",runner.action?.expectedLabel)
        runner.acknowledge(runner.action!!.request,true,2000)
        runner.process(sized(table),3000)
        assertEquals(0,store.writes,"Old page size data must not be imported")
        runner.process(sized(table.copy(page=1,pages=1,pageSize=100)),4000)
        assertEquals(1,store.writes); assertEquals(1,runner.summary!!.records.size)
        runner.requireSync()
        runner.process(sized(table.copy(page=1,pages=1,pageSize=100)),5000)
        assertEquals("size",runner.action?.element,"Recheck available maximum for each import")
    }

    @Test fun `given storage fails then block course actions and keep previous saved history`() = runTest {
        val store=Store().apply { fails=true }; val runner=CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        runner.process(snapshot(ProgressTable(true,page=1,pages=1,total=1,rows=listOf(row()),unfiltered=true)),1000)
        assertNull(runner.summary); assertTrue(runner.blocksActions); assertNull(runner.action)
        assertTrue(runner.detail.contains("Could not save progress"))
    }

    @Test fun `given chapters repeat part numbers then keep lessons distinct and update observed completion`() = runTest {
        val store=Store(); val runner=CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        val empty=ProgressTable(true,page=1,pages=1,total=0,unfiltered=true)
        runner.process(snapshot(empty),1000); runner.process(snapshot(empty),2000)
        runner.acknowledge(runner.action!!.request,true,2000)
        val course=snapshot().copy(path="/dashboard/courses/module",elements=listOf(
            CourseElement("chapter",label="1.1. First chapter",expanded=true),
            CourseElement("watch",label="Watch video",context="Part:1 Not started")))
        assertFalse(runner.process(course,3000))
        assertFalse(runner.process(course.copy(elements=listOf(course.elements.first(),course.elements.last().copy(label="Rewatch video",context="Part:1 Completed"))),4000))
        assertFalse(runner.process(course.copy(elements=listOf(course.elements.first().copy(label="1.2. Second chapter"),course.elements.last())),5000))
        val lessons=store.saved.values.filter { it.kind=="lesson" }
        assertEquals(2,lessons.size); assertEquals(1,lessons.count { it.status=="completed" })
    }

    @Test fun `given chapter test passes then do not classify it as a whole module pass`() {
        val records = assessmentRecords("user",listOf(row() + ("Test Title" to "Chapter Assessment")),1)!!
        assertEquals("test",records.single().kind)
    }

    @Test fun `given a local profile belongs to another user then do not merge records`() = runTest {
        val store = Store().apply { profiles = listOf(LearningAccount("sagarmeinyog:existing","Another person",1)) }
        val runner = CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"existing")
        runner.process(snapshot(),1000)
        assertEquals(0,store.writes); assertNull(runner.action)
        assertTrue(runner.detail.contains("different displayed user"))
    }

    @Test fun `given paginated history then save all pages before courses and retain earlier pass`() = runTest {
        val store = Store(); val runner = CourseProgressSession(store,"run",0)
        assertTrue(runner.process(snapshot(),0)); assertNotNull(runner.request)
        assertFalse(runner.confirm("stale","Local user")); assertTrue(runner.confirm(runner.request!!.token,"Local user"))
        runner.process(snapshot(),1); assertEquals("progress",runner.action?.element); runner.acknowledge(runner.action!!.request,true,1)
        val first = ProgressTable(true,page=1,pages=2,total=2,rows=listOf(row()),next="next",unfiltered=true)
        runner.process(snapshot(first),1001); assertEquals(0,store.writes); assertEquals("next",runner.action?.element)
        runner.acknowledge(runner.action!!.request,true,1001)
        runner.process(snapshot(first.copy(page=2,rows=listOf(row("NOT SUBMITTED","2")),next=null,first="first")),2001)
        assertEquals(1,store.writes); assertEquals(2,runner.summary!!.records.size)
        assertEquals(1,runner.summary!!.records.count { it.status == "passed" })
        runner.process(snapshot(first),3001); assertEquals("home",runner.action?.element)
        runner.acknowledge(runner.action!!.request,true,3001)
        assertFalse(runner.process(snapshot(),4001))
        runner.requireSync(); assertTrue(runner.process(snapshot(),5001))
        assertEquals("progress",runner.action?.element)
    }
    @Test fun `given filtered loading or changed user then no import and no learning action`() = runTest {
        val store=Store(); val runner=CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person one")
        val table=ProgressTable(true,page=1,pages=1,total=1,rows=listOf(row()),unfiltered=false)
        assertTrue(runner.process(snapshot(table),1000)); assertEquals(0,store.writes)
        assertTrue(runner.process(snapshot(table.copy(unfiltered=true),name="Someone else"),2000,learningActionPending=true)); assertNull(runner.action)
        assertTrue(runner.detail.contains("user changed")); assertEquals(0,store.writes)
    }
    @Test fun `given invalid result or partial row counts then do not save or assume pass`() = runTest {
        assertNull(assessmentRecords("a",listOf(row("MAYBE")),0))
        val store=Store(); val runner=CourseProgressSession(store,"run",0)
        runner.process(snapshot(),0); runner.confirm(runner.request!!.token,"Person")
        runner.process(snapshot(ProgressTable(true,page=1,pages=1,total=2,rows=listOf(row()),unfiltered=true)),1000)
        assertNull(runner.summary); assertEquals(0,store.writes); assertTrue(runner.isChecking)
    }
}
