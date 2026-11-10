package com.jagdeep.home.data

import com.jagdeep.home.domain.*
import com.jagdeep.persistence.room.learning.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DgsStudyDatabaseTest {
    @Test fun `DGS sessions survive database reopen and isolate users courses and existing Sagar rows`()=runBlocking {
        val folder=Files.createTempDirectory("dgs-history").toFile();val path=folder.resolve("history.db").path
        var store=openLearningProgressStore(path)
        try {
            store.save(LearningAccount("sagar","Existing",1),listOf(LearningRecord("sagar","assessment","Module","assessment","Test","passed",observedAt=1)))
            var repository=StoredDgsStudyRepository(RoomCourseProgressRepository(store))
            val session=DgsStudySession(id="one",courseId="course-a",courseName="Course A",displayName="User",startedAt=0,updatedAt=5000,activeMillis=5000,baselineHours=2.0,requiredHours=18.0,page="Lesson",pages=listOf(DgsPageProgress("p","Lesson",5000,true,200.0)),videos=listOf(DgsVideoProgress("p","v",10.0,10.0,true)))
            repository.save("alice",session);repository.save("alice",session)
            repository.save("bob",session.copy(id="two",activeMillis=3000))
            repository.save("alice",session.copy(id="three",courseId="course-b",activeMillis=1000))
            store.close();store=openLearningProgressStore(path);repository=StoredDgsStudyRepository(RoomCourseProgressRepository(store))
            val restored=repository.load("alice","course-a")
            assertEquals(5000,restored.activeMillis);assertEquals(true,restored.sessions.single().pages.single().read)
            assertEquals(true,restored.sessions.single().videos.single().ended)
            assertEquals(3000,repository.load("bob","course-a").activeMillis);assertEquals(1000,repository.load("alice","course-b").activeMillis)
            assertEquals("passed",store.records("sagar").single().status)
            assertFailsWith<IllegalArgumentException>{repository.save("alice",session.copy(activeMillis=-1))}
            Unit
        }finally {store.close();folder.deleteRecursively()}
    }
}
