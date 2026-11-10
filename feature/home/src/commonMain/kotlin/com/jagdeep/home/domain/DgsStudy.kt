package com.jagdeep.home.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class DgsPageProgress(val id:String,val title:String,val activeMillis:Long=0,val read:Boolean=false,val scroll:Double=0.0)
@Serializable data class DgsVideoProgress(val page:String,val id:String,val position:Double=0.0,val duration:Double?=null,val ended:Boolean=false,val speed:Double=1.0)
@Serializable data class DgsStudySession(val version:Int=1,val id:String,val courseId:String,val courseName:String,val displayName:String,
    val startedAt:Long,val updatedAt:Long,val activeMillis:Long,val baselineHours:Double,val requiredHours:Double,
    val page:String,val pages:List<DgsPageProgress> = emptyList(),val videos:List<DgsVideoProgress> = emptyList(),val status:String="studying")
data class DgsStudyHistory(val sessions:List<DgsStudySession>) {
    val activeMillis get()=sessions.sumOf { it.activeMillis }
    val first get()=sessions.minByOrNull {it.startedAt}
}
interface DgsStudyRepository {
    val available:Boolean
    suspend fun load(username:String,courseId:String):DgsStudyHistory
    suspend fun save(username:String,value:DgsStudySession)
}
object NoDgsStudyRepository:DgsStudyRepository {
    override val available=false
    override suspend fun load(username:String,courseId:String)=DgsStudyHistory(emptyList())
    override suspend fun save(username:String,value:DgsStudySession)=error("Storage unavailable")
}

/** One serial writer. Each checkpoint replaces the same run's absolute counter, never adds a delta. */
internal class DgsStudyCoordinator(private val repository:DgsStudyRepository,private val runner:DgsSession,private val routine:DgsELearningRoutine,private val startedAt:Long) {
    private val mutex=Mutex()
    private var username=""
    private var account=""
    private var course=""
    private var courseName=""
    private var loaded=false
    private var persistedAt=0L
    private var previousPage=""
    private var baseline=routine.creditedHours
    private var required=routine.requiredHours
    private var failed=false
    private var savedTotal=0L
    private val videos=linkedMapOf<String,DgsVideoProgress>()

    suspend fun read(value:DgsReading,now:Long,elapsed:Long=now)=mutex.withLock {
        if(!value.ready) {runner.read(value,elapsed);return@withLock}
        if(failed) {runner.persistencePause("Progress could not be saved. Stop and restart after checking local storage.");return@withLock}
        if(!repository.available) {runner.persistencePause("Progress storage is unavailable. This course cannot start safely.");return@withLock}
        val observed=value.accountName.trim().replace(Regex("\\s+")," ").uppercase()
        val identity=value.courseId
        if(observed.isBlank() || identity.isBlank()) {runner.persistencePause("Cannot identify the signed-in DGS user and course. Open the course from DGS Home.");return@withLock}
        if(!loaded) username=observed.lowercase()
        if(loaded && (account!=observed || course!=identity)) {runner.persistencePause("DGS user or course changed. Stop and start again to select the correct saved progress.");return@withLock}
        try {
            if(!loaded) {
                account=observed;course=identity
                val history=repository.load(username,course)
                courseName=runner.progress.courseName ?: value.courseName.ifBlank {history.first?.courseName.orEmpty()}
                if(courseName.isBlank()) {runner.persistencePause("Open your course from DGS Home so its name can be saved with your profile.");return@withLock}
                if(history.sessions.any {it.displayName!=observed}) {runner.persistencePause("This username belongs to a different saved DGS profile. Stop and confirm the correct username.");return@withLock}
                baseline=history.first?.baselineHours ?: baseline;required=history.first?.requiredHours ?: required
                savedTotal=history.activeMillis
                val pages=history.sessions.sortedBy {it.updatedAt}.flatMap {it.pages}.groupBy {it.id}.map {(_,rows)-> rows.last().copy(activeMillis=rows.maxOf {it.activeMillis},read=rows.any {it.read})}
                history.sessions.sortedBy {it.updatedAt}.flatMap {it.videos}.forEach {videos[it.page+":"+it.id]=it}
                runner.restoreStudy(username,account,courseName,savedTotal,baseline,required,pages)
                loaded=true
            }
            runner.read(value,elapsed)
            value.videos.forEach {video ->
                val key=value.page+":"+video.id;val previous=videos[key]
                videos[key]=video.copy(page=value.page,ended=video.ended || previous?.ended==true,duration=video.duration ?: previous?.duration)
            }
            runner.videoCounts(videos.size,videos.values.count {it.ended})
            if(elapsed-persistedAt>=5_000 || previousPage!=value.page) save(now,"studying",elapsed)
            previousPage=value.page
        } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(_:Exception) {failed=true;runner.persistencePause("Could not save DGS progress. Check free disk space; navigation is paused.")}
    }
    suspend fun checkpoint(now:Long,status:String="studying",elapsed:Long=now)=mutex.withLock {
        if(loaded && !failed) try {if(status!="studying" || elapsed-persistedAt>=5_000) save(now,status,elapsed)}
        catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(_:Exception) {failed=true;runner.persistencePause("Could not save DGS progress. Navigation is paused.")}
    }
    private suspend fun save(now:Long,status:String,elapsed:Long) {
        repository.save(username,DgsStudySession(id=runner.session,courseId=course,courseName=courseName,displayName=account,
            startedAt=startedAt,updatedAt=maxOf(now,startedAt),activeMillis=(runner.progress.activeMillis-savedTotal).coerceAtLeast(0),baselineHours=baseline,requiredHours=required,
            page=runner.progress.page,pages=runner.savedPages(),videos=videos.values.toList(),status=status))
        persistedAt=elapsed;runner.savedAt(now)
    }
}
