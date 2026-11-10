package com.jagdeep.home.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DgsStudyTest {
    private class Store:DgsStudyRepository {
        override val available=true
        var fails=false
        val rows=mutableMapOf<Pair<String,String>,DgsStudySession>()
        override suspend fun load(username:String,courseId:String)=DgsStudyHistory(rows.filter {it.key.first==username && it.value.courseId==courseId}.values.toList())
        override suspend fun save(username:String,value:DgsStudySession) {if(fails)error("disk full");rows[username to value.id]=value}
    }
    private val routine=DgsELearningRoutine("r","DGS")
    private fun reading(session:String,course:String="course-a",account:String="Alice",focused:Boolean=true,media:String="none")=DgsReading(session=session,page="page1",title="Lesson",ready=true,focused=focused,accountName=account,courseId=course,courseName="Example course",next=true,media=media)
    @Test fun `stop and restart restore total without double counting duplicate checkpoints`()=runTest {
        val db=Store();val a=DgsSession("s1",routine);val first=DgsStudyCoordinator(db,a,routine,0)
        first.read(reading("s1"),0);assertEquals("ALICE",a.progress.accountName)
        for(t in 0L..10000L step 500)first.read(reading("s1"),t)
        first.checkpoint(10000,"stopped");first.checkpoint(10000,"stopped")
        assertEquals(10000,db.load("alice","course-a").activeMillis)
        val b=DgsSession("s2",routine.copy(creditedHours=5.0));val second=DgsStudyCoordinator(db,b,routine.copy(creditedHours=5.0),20000)
        for(t in 20000L..25000L step 500)second.read(reading("s2"),t)
        second.checkpoint(25000,"stopped")
        assertEquals(15000,b.progress.activeMillis);assertEquals(5000,b.progress.currentRunMillis)
        assertEquals(0.0,b.progress.baselineHours,"Initial baseline is not overwritten on restart")
        assertEquals(15000,db.load("alice","course-a").activeMillis)
        assertEquals(0,db.load("bob","course-a").activeMillis);assertEquals(0,db.load("alice","course-b").activeMillis)
    }
    @Test fun `focus loss blocked playback and account changes never credit time`()=runTest {
        val db=Store();val s=DgsSession("s",routine);val c=DgsStudyCoordinator(db,s,routine,0)
        for(t in 0L..5000L step 500)c.read(reading("s"),t)
        for(t in 5500L..10000L step 500)c.read(reading("s",focused=false),t)
        for(t in 10500L..15000L step 500)c.read(reading("s",media="buffering"),t)
        for(t in 15500L..20000L step 500)c.read(reading("s",account="User B"),t)
        assertEquals(5000,s.progress.activeMillis);assertEquals("pause",s.command.action)
        c.checkpoint(20000,"stopped");assertEquals(5000,db.load("alice","course-a").activeMillis)
    }
    @Test fun `disk failure pauses navigation and cannot manufacture saved progress`()=runTest {
        val db=Store();db.fails=true;val s=DgsSession("s",routine);val c=DgsStudyCoordinator(db,s,routine,0)
        for(t in 0L..10000L step 500)c.read(reading("s"),t)
        assertEquals("pause",s.command.action);assertTrue(s.progress.needsAttention);assertTrue(db.rows.isEmpty())
    }
    @Test fun `distinct DGS display names stay isolated`()=runTest {
        val db=Store()
        for((i,name) in listOf("alice","bob").withIndex()) {
            val s=DgsSession("s$i",routine);val c=DgsStudyCoordinator(db,s,routine,0)
            for(t in 0L..5000L step 500)c.read(reading("s$i",account=name),t)
            c.checkpoint(5000,"stopped")
        }
        assertEquals(5000,db.load("alice","course-a").activeMillis);assertEquals(5000,db.load("bob","course-a").activeMillis)
    }
    @Test fun `completed video history survives a replay after restart`()=runTest {
        val db=Store()
        db.save("alice",DgsStudySession(id="old",courseId="course-a",courseName="Example course",displayName="ALICE",startedAt=0,updatedAt=1000,activeMillis=1000,baselineHours=0.0,requiredHours=18.0,page="Lesson",videos=listOf(DgsVideoProgress("page1","video",10.0,10.0,true))))
        val runner=DgsSession("new",routine);val coordinator=DgsStudyCoordinator(db,runner,routine,2000)
        coordinator.read(reading("new").copy(videos=listOf(DgsVideoProgress("page1","video",0.0,10.0,false))),2000)
        assertEquals(1,runner.progress.completedVideos)
        coordinator.checkpoint(2000,"stopped")
        assertTrue(db.rows["alice" to "new"]!!.videos.single().ended)
    }

    @Test fun `system clock changes do not alter monotonic study time or checkpoint frequency`()=runTest {
        val db=Store();val runner=DgsSession("s",routine);val c=DgsStudyCoordinator(db,runner,routine,1_000_000)
        for(t in 0L..5000L step 500)c.read(reading("s"),1_000_000-t,t)
        assertEquals(5000,runner.progress.activeMillis)
        assertEquals(5000,db.load("alice","course-a").activeMillis)
    }

}
