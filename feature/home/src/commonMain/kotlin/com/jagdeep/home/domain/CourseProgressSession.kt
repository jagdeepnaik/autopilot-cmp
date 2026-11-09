package com.jagdeep.home.domain

import com.jagdeep.persistence.room.learning.*
import kotlinx.coroutines.flow.first

/** Serial snapshot consumer. A complete, validated import must be durable before navigation resumes. */
internal class CourseProgressSession(private val repository: CourseProgressRepository, private val token: String, private val wait: Long) {
    private var identityMissing = true
    val blocksActions get() = fatal || request != null || identityMissing
    val isChecking get() = phase != "ready" || fatal
    var request: ProgressProfileRequest? = null; private set
    var summary: CourseProgressSummary? = null; private set
    var action: CourseAction? = null; private set
    var detail = "Checking the signed-in user and saved progress."; private set
    private var profile: LearningAccount? = null
    private var phase = "check"
    private var sequence = 1_000_000_000L
    private var pendingAt = 0L
    private var nextAt = 0L
    private var expectedPage = 1
    private var totalPages = 0
    private var totalRows = 0
    private var sizeChecked = false
    private var sizeOpened = false
    private var sizeTarget: Int? = null
    private var scanSize: Int? = null
    private val collected = mutableListOf<Map<String,String>>()
    private var profileChecked = false
    private var lessonDigest = ""
    private var fatal = false
    fun confirm(requestToken: String, localName: String): Boolean {
        val current = request?.takeIf { it.token == requestToken } ?: return false
        val name = localName.trim()
        if (name.isEmpty() || name.length > 120) return false
        profile = LearningAccount("sagarmeinyog:" + CourseAnswerBank.normalize(name), current.displayName, 0)
        request = null
        return true
    }
    fun requireSync() { if (!fatal) { phase = "check"; expectedPage = 1; totalPages = 0; totalRows = 0; collected.clear(); sizeChecked = false; sizeOpened = false; sizeTarget = null; scanSize = null } }
    fun acknowledge(id: Long, success: Boolean, now: Long): Boolean {
        if (action?.request != id) return false
        action = null
        if (!success) { fatal = true; detail = "Progress navigation was refused. Stop and restart after checking the page." }
        nextAt = maxOf(nextAt, now + wait.coerceAtLeast(1000))
        return true
    }
    private fun click(snapshot: CourseSnapshot, id: String?, now: Long, message: String, kind: String = "click") {
        val element = snapshot.elements.singleOrNull { it.id == id && it.visible && it.enabled } ?: run { detail = message; return }
        action = CourseAction(++sequence, snapshot.document, element.id, kind, element.label.takeIf { kind == "progressPageSize" })
        pendingAt = now
        detail = message
    }
    suspend fun process(snapshot: CourseSnapshot, now: Long, learningActionPending: Boolean = false): Boolean {
        if (!snapshot.top) return phase != "ready"
        if (fatal) return true
        if (snapshot.session != token || !snapshot.ready || snapshot.truncated) { identityMissing = true; detail = "Waiting for a complete page to check progress."; return true }
        val name = snapshot.profileName?.trim()?.takeIf { it.isNotBlank() }
        if (name == null) { identityMissing = true; detail = "Sign in to Sagar Mein Yog in Chrome, or reload the updated extension. Waiting for the displayed user name."; return true }
        identityMissing = false
        if (profile != null && profile!!.displayName != name) {
            fatal = true; action = null; detail = "Signed-in user changed. Stop and restart to choose the correct progress profile."; return true
        }
        if (profile == null) {
            request = ProgressProfileRequest("$token:$name", name)
            detail = "Confirm the progress profile for $name in the app."
            return true
        }
        if (learningActionPending) return false
        if (action != null) {
            if (now - pendingAt > 15_000) { fatal = true; action = null; detail = "Progress navigation was not confirmed. Stop and restart to retry." }
            return true
        }
        if (now < nextAt && phase != "ready") return true
        val account = profile!!
        if (!profileChecked) {
            try {
                val existing = repository.accounts().first().singleOrNull { it.key == account.key }
                if (existing != null && existing.displayName != account.displayName) {
                    fatal = true; detail = "This local profile belongs to a different displayed user. Stop, restart and choose a different local profile name."; return true
                }
                profileChecked = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fatal = true; detail = "Could not verify the local profile. Stop and restart after checking storage."; return true }
        }
        val isProgress = snapshot.path.trimEnd('/') == "/dashboard/progress"
        // Never leave a live timed attempt in order to refresh history.
        if (phase != "ready" && snapshot.assessment?.let { it.result == null && it.questions.isNotEmpty() } == true) {
            detail = "An assessment is active. Finish it manually before starting a progress-checked run."; return true
        }
        if (phase == "leaving" && !isProgress) phase = "ready"
        if (phase == "leaving") { detail = "Waiting for the course dashboard."; return true }
        if (phase == "ready" && !isProgress) {
            val rows = snapshot.elements.filter { it.label.trim().lowercase() in setOf("watch video", "rewatch video", "resume video") }
            if (rows.isNotEmpty() && snapshot.path.startsWith("/dashboard/courses/")) {
                val course = snapshot.elements.firstOrNull { it.href == snapshot.path }?.label ?: snapshot.path
                val chapter = snapshot.elements.firstOrNull { it.expanded == true && Regex("^\\s*\\d+\\.\\d+|chapter",RegexOption.IGNORE_CASE).containsMatchIn(it.label) }?.label
                    ?.replace(Regex("(?i)not started|in progress|not completed|completed|\\d+\\s*%"), "")?.trim().orEmpty()
                val observations = rows.map { row ->
                    val key = row.href ?: row.context.replace(Regex("(?i)not started|in progress|not completed|completed|rewatch video|resume video|watch video"), "").trim()
                    val done = row.label.equals("Rewatch video", true) || Regex("(?i)completed").containsMatchIn(row.context) && !Regex("(?i)not completed|not started|in progress").containsMatchIn(row.context)
                    LearningRecord(account.key,"lesson:${snapshot.path}:$chapter:$key",course,"lesson",row.context.take(1000),if(done) "completed" else "incomplete",details = row.href.orEmpty(),observedAt = now)
                }
                val digest = observations.joinToString { it.key + it.status }
                if (digest != lessonDigest) {
                    try { repository.save(account, observations); lessonDigest = digest }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { detail = "Could not save lesson progress. Check local storage and restart."; fatal = true; return true }
                }
            }
            return false
        }
        if (!isProgress) {
            click(snapshot, snapshot.elements.firstOrNull { it.href == "/dashboard/progress" }?.id, now, "Opening Your Progress before continuing courses.")
            return true
        }
        if (phase == "ready") requireSync()
        val table = snapshot.progress
        if (table == null || !table.ready || table.error != null) {
            val retry = snapshot.elements.firstOrNull { it.tag == "button" && it.label.equals("Try again",true) && it.visible && it.enabled }
            if (retry != null && table?.error != null) {
                requireSync()
                click(snapshot,retry.id,now,"Progress could not load. Retrying in Chrome; saved history is unchanged.")
                nextAt = now + wait.coerceAtLeast(10000)
            } else detail = table?.error ?: "Waiting for the progress table to load. Previous saved progress is unchanged."
            return true
        }
        if (!table.unfiltered) { detail = "In Your Progress choose All Time and clear table filters, then leave Chrome in front."; return true }
        if (table.page !in 1..table.pages.coerceAtLeast(1) || table.pages > 2000 || table.total !in 0..20000) {
            detail = "Progress pagination is not recognized. Saved progress is unchanged."; return true
        }
        if (phase == "return") {
            click(snapshot, snapshot.elements.firstOrNull { it.href == "/dashboard" }?.id, now, "Progress saved for ${account.displayName}. Returning to courses.")
            phase = "leaving"
            return true
        }
        if (!sizeChecked && table.pageSizeControl != null) {
            if (sizeTarget != null) {
                if (table.pageSize != sizeTarget || table.pageSizeOpen) {
                    detail = "Waiting for $sizeTarget rows per page to load."; return true
                }
                sizeChecked = true
            } else if (table.pageSizeOpen) {
                val choices = table.pageSizeOptions.filter { choice ->
                    choice.value in 1..20000 && snapshot.elements.any { it.id == choice.id && it.visible && it.enabled }
                }
                val maximum = choices.maxByOrNull { it.value }
                if (maximum == null || choices.count { it.value == maximum.value } != 1) {
                    detail = "Page-size choices are unavailable or ambiguous. Check Rows per page in Chrome."; return true
                }
                sizeTarget = maximum.value
                click(snapshot,maximum.id,now,"Selecting the largest available page size: ${maximum.value} rows.",kind="progressPageSize")
                return true
            } else if (!sizeOpened) {
                sizeOpened = true
                click(snapshot,table.pageSizeControl,now,"Checking the largest available Rows per page option.",kind="progressPageSize")
                return true
            } else {
                detail = "Waiting for Rows per page choices. Reopen that menu in Chrome if it was closed."; return true
            }
        }
        if (scanSize != null && table.pageSize != scanSize) { requireSync(); detail = "Page size changed. Restarting the progress check."; return true }
        if (totalPages == 0) scanSize = table.pageSize
        if (expectedPage == 1 && table.page != 1) { click(snapshot,table.first,now,"Opening the first page of progress history."); return true }
        if (table.page != expectedPage) { detail = "Waiting for progress page $expectedPage."; return true }
        if (totalPages == 0) { totalPages = table.pages; totalRows = table.total }
        if (table.pages != totalPages || table.total != totalRows) { requireSync(); detail = "Progress history changed while reading it. Checking again from page one."; return true }
        if (assessmentRecords(account.key, table.rows, now) == null) { detail = "Progress contains unrecognized rows. Saved history is unchanged."; return true }
        collected.addAll(table.rows)
        if (table.page < table.pages) {
            if (table.next == null) { collected.clear(); fatal = true; detail = "Progress pagination is incomplete. Stop and restart; saved history is unchanged."; return true }
            expectedPage++
            click(snapshot,table.next,now,"Reading progress page ${table.page} of ${table.pages} for ${account.displayName}.")
            return true
        }
        if (collected.size != totalRows) { fatal = true; detail = "Progress row count changed. Stop and restart; saved history is unchanged."; return true }
        val records = assessmentRecords(account.key,collected,now) ?: return true
        try {
            val checked = account.copy(checkedAt = now)
            repository.save(checked, records)
            summary = CourseProgressSummary(account.key,account.displayName,now,repository.records(account.key))
            profile = checked
            phase = "return"
            detail = "Saved ${records.size} assessment and quiz attempts for ${account.displayName}."
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fatal = true; detail = "Could not save progress. Check local storage and restart. Courses remain paused." }
        return true
    }
}
