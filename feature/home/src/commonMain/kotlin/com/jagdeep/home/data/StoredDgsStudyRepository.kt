package com.jagdeep.home.data

import com.jagdeep.home.domain.*
import com.jagdeep.persistence.room.learning.LearningAccount
import com.jagdeep.persistence.room.learning.LearningRecord
import kotlinx.serialization.json.Json

/** Reuses the existing learning database without changing its schema or Sagar records. */
class StoredDgsStudyRepository(private val storage:CourseProgressRepository):DgsStudyRepository {
    override val available get()=storage.available
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    private fun account(username:String)="dgs:"+username.trim().lowercase()
    override suspend fun load(username:String,courseId:String):DgsStudyHistory {
        val records=storage.records(account(username)).filter {it.course==courseId}
        val values=records.filter {it.kind=="dgs-study"}.map { row ->
            val session=json.decodeFromString(DgsStudySession.serializer(),row.details)
            session.copy(pages=records.filter {it.kind=="dgs-page" && it.key.startsWith("dgs-page:${session.id}:")}.map {json.decodeFromString(DgsPageProgress.serializer(),it.details)},
                videos=records.filter {it.kind=="dgs-video" && it.key.startsWith("dgs-video:${session.id}:")}.map {json.decodeFromString(DgsVideoProgress.serializer(),it.details)}).also(::validate)
        }
        return DgsStudyHistory(values.distinctBy {it.id})
    }
    override suspend fun save(username:String,value:DgsStudySession) {
        require(username.trim().length in 1..200);validate(value)
        val key=account(username)
        val rows=mutableListOf(LearningRecord(key,"dgs:${value.id}",value.courseId,"dgs-study",value.courseName,value.status,
            details=json.encodeToString(DgsStudySession.serializer(),value.copy(pages=emptyList(),videos=emptyList())),observedAt=value.updatedAt))
        value.pages.forEach { page -> rows+=LearningRecord(key,"dgs-page:${value.id}:${page.id}",value.courseId,"dgs-page",page.title,if(page.read) "read" else "observed",details=json.encodeToString(DgsPageProgress.serializer(),page),observedAt=value.updatedAt) }
        value.videos.forEach { video -> rows+=LearningRecord(key,"dgs-video:${value.id}:${video.page}:${video.id}",value.courseId,"dgs-video",value.courseName,if(video.ended) "ended" else "observed",details=json.encodeToString(DgsVideoProgress.serializer(),video),observedAt=value.updatedAt) }
        storage.save(LearningAccount(key,"DGS · ${value.displayName}",value.updatedAt),rows)
    }
    private fun validate(v:DgsStudySession) {
        require(v.version==1 && v.id.length in 1..200 && v.courseId.length in 1..500 && v.courseName.length in 1..500 && v.displayName.length in 1..200)
        require(v.activeMillis>=0 && v.startedAt>=0 && v.updatedAt>=v.startedAt)
        require(v.baselineHours.isFinite() && v.requiredHours.isFinite() && v.baselineHours>=0 && v.requiredHours>0 && v.baselineHours<=v.requiredHours)
        require(v.pages.size<=500 && v.videos.size<=200)
        require(v.pages.all {it.id.length in 1..200 && it.title.length<=240 && it.activeMillis>=0 && it.scroll.isFinite() && it.scroll>=0})
        require(v.videos.all {it.id.length in 1..300 && it.position.isFinite() && it.position>=0 && (it.duration==null || it.duration.isFinite() && it.duration>=0) && it.speed.isFinite() && it.speed>0})
    }
}
