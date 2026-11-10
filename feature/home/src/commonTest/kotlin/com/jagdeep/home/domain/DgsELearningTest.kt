package com.jagdeep.home.domain

import kotlin.test.*

class DgsELearningTest {
    private val routine = DgsELearningRoutine("d","DGS",requiredHours=0.02)
    private fun reading(page:String="one",bottom:Boolean=true,next:Boolean?=true,previous:Boolean?=false,media:String="none",focused:Boolean=true,problem:String?=null) = DgsReading(session="s",page=page,title=page,ready=true,focused=focused,atBottom=bottom,next=next,previous=previous,media=media,problem=problem,first=true)
    private fun advance(s:DgsSession,from:Long,to:Long,r:DgsReading) { for(t in from..to step 500) s.read(r,t) }

    @Test fun `given numbered course choices then wait for explicit available selection and timeout safely`() {
        val s=DgsSession("s",routine)
        val home=reading(page="home").copy(ready=false,courses=listOf(DgsCourseChoice("a","Course A","18",true),DgsCourseChoice("b","Course B","20",false)))
        s.read(home,0)
        assertEquals(1,s.progress.courses.size);assertEquals("pause",s.command.action)
        s.selectCourse("b",500);assertEquals("pause",s.command.action)
        s.selectCourse("unknown",500);assertEquals("pause",s.command.action)
        s.selectCourse("a",500)
        assertEquals("selectCourse",s.command.action);assertEquals("a",s.command.courseId)
        assertTrue(s.progress.courses.isEmpty())
        s.read(home,1000);assertEquals("selectCourse",s.command.action)
        s.read(home,16000);assertEquals("pause",s.command.action);assertEquals(1,s.progress.courses.size)
        assertEquals(0,s.progress.activeMillis)
        s.selectCourse("a",16500);assertEquals(2,s.command.selectionRequest);s.read(reading().copy(course="course-a"),17000)
        assertTrue(s.progress.courses.isEmpty());assertEquals("Course A",s.progress.courseName)
    }
    @Test fun `given only unavailable courses then show an empty available state without opening anything`() {
        val s=DgsSession("s",routine)
        s.read(reading().copy(ready=false,courses=listOf(DgsCourseChoice("x","Expired",available=false))),0)
        assertTrue(s.progress.courses.isEmpty());assertTrue(s.progress.detail.startsWith("No available courses"));assertEquals("pause",s.command.action)
    }
    @Test fun `given account or course changes then pause without carrying study time across courses`() {
        val s=DgsSession("s",routine)
        s.read(reading(bottom=false).copy(course="a"),0)
        s.read(reading(bottom=false).copy(course="a"),500)
        s.read(reading(bottom=false).copy(course="b"),1000)
        assertEquals("pause",s.command.action);assertEquals(500,s.progress.activeMillis)
        assertTrue(s.progress.detail.contains("course changed"))
    }
    @Test fun `given invalid or nonfinite hours when validating then reject`() {
        for(value in listOf("0","-1","NaN","Infinity","1001","bad")) assertTrue(RoutineDraft(kind=RoutineKind.DgsELearning,name="DGS",requiredHours=value).validate().hasAny,value)
        assertTrue(RoutineDraft(kind=RoutineKind.DgsELearning,name="DGS",creditedHours="19").validate().hasAny)
        assertEquals(1.0,routine.playbackSpeed)
        assertEquals(DGS_ANSWER_BANK,routine.answerBankFile)
        assertFalse(routine.needsInputAccess())
        assertEquals(routine,routine.toDraft().toRoutine("d"))
    }
    @Test fun `given a video playing at bottom when reading then never navigate until it ends`() {
        val s=DgsSession("s",routine)
        advance(s,0,40_000,reading(media="playing"))
        assertEquals("read",s.command.action)
        assertEquals(0,s.progress.pagesRead)
        advance(s,40_500,45_500,reading(media="ended"))
        assertEquals("next",s.command.action)
        assertEquals(1,s.progress.pagesRead)
    }
    @Test fun `browser click acknowledgement is counted once even after the page transitions`() {
        val s=DgsSession("s",routine)
        advance(s,0,30_000,reading())
        val request=s.command.request!!
        s.read(reading(page="two",previous=true),30_500)
        val event=DgsBrowserAction("dgsBrowserAction","s",request,true)
        s.acknowledgeBrowserAction(event);s.acknowledgeBrowserAction(event)
        assertEquals(1,s.progress.clicks)
        s.acknowledgeBrowserAction(event.copy(session="old",request="unknown"));assertEquals(1,s.progress.clicks)
    }
    @Test fun `browser click refusal pauses with a useful message without retrying`() {
        val s=DgsSession("s",routine);advance(s,0,30_000,reading())
        s.acknowledgeBrowserAction(DgsBrowserAction("dgsBrowserAction","s",s.command.request!!,false,"busy"))
        assertEquals("pause",s.command.action);assertTrue(s.progress.detail.contains("Another browser controller"))
    }
    @Test fun `navigation timeout excludes time spent outside foreground Chrome`() {
        val s=DgsSession("s",routine);advance(s,0,30_000,reading())
        advance(s,30_500,60_000,reading(focused=false));s.read(reading(),60_500)
        assertEquals("next",s.command.action);assertFalse(s.progress.needsAttention)
    }
    @Test fun `given foreground loss blocked media stale messages or verification then exclude elapsed time`() {
        val s=DgsSession("s",routine)
        advance(s,0,5000,reading(bottom=false))
        advance(s,5500,15000,reading(focused=false))
        advance(s,15500,25000,reading(media="needsPlay"))
        advance(s,25500,35000,reading(problem="Verification"))
        assertEquals(5000,s.progress.activeMillis)
        s.stale();s.read(reading(bottom=false),100_000)
        assertEquals(5000,s.progress.activeMillis)
        s.read(reading().copy(session="old"),100_500)
        assertEquals(5000,s.progress.activeMillis)
    }
    @Test fun `given short page when study time remains then dwell and wait for page transition exactly once`() {
        val s=DgsSession("s",routine)
        advance(s,0,29_500,reading())
        assertEquals("read",s.command.action)
        s.read(reading(),30_000);assertEquals("next",s.command.action)
        s.read(reading(),30_500);assertEquals("next",s.command.action)
        advance(s,31_000,46_500,reading())
        assertTrue(s.progress.needsAttention)
        assertEquals(30_000,s.progress.activeMillis)
        s.read(reading(page="two",previous=true),47_000)
        assertEquals("read",s.command.action)
    }
    @Test fun `given last page before hours target then return to first and complete only after target and final page`() {
        val s=DgsSession("s",routine)
        advance(s,0,30_000,reading())
        advance(s,30_500,61_000,reading(page="two",next=false,previous=true))
        assertEquals("first",s.command.action);assertFalse(s.completed)
        advance(s,61_500,92_000,reading())
        assertEquals("next",s.command.action)
        advance(s,92_500,123_000,reading(page="two",next=false,previous=true))
        assertTrue(s.completed);assertEquals("pause",s.command.action)
    }
    @Test fun `given start midway or unknown boundary then continue forward and pause on unknown boundary`() {
        val s=DgsSession("s",routine)
        advance(s,0,30_000,reading(previous=true,next=true))
        assertEquals("next",s.command.action)
        val unknown=DgsSession("s",routine)
        advance(unknown,0,35_000,reading(previous=null,next=null))
        assertEquals("pause",unknown.command.action);assertTrue(unknown.progress.needsAttention)
    }
}
