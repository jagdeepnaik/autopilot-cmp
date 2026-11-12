package com.jagdeep.home.domain

import com.jagdeep.persistence.room.learning.*
import kotlinx.coroutines.flow.first

/** Serial snapshot consumer. A complete, validated import must be durable before navigation resumes. */
internal class CourseProgressSession(private val repository: CourseProgressRepository, private val token: String, private val wait: Long) {
    val blocksActions get() = fatal || request != null
    val isChecking get() = request != null || fatal
    var request: ProgressProfileRequest? = ProgressProfileRequest("$token:profile", loading = true); private set
    var summary: CourseProgressSummary? = null; private set
    var hasLocalChanges = false; private set
    var action: CourseAction? = null; private set
    var detail = "Choose a saved progress profile or create one to continue."; private set
    private var profile: LearningAccount? = null
    private var phase = "check"
    private var sequence = 1_000_000_000L
    private var pendingAt = 0L
    private var nextAt = 0L
    private var expectedPage = 1
    private var totalPages = 0
    private var totalRows = 0
    private var sizeChecked = false
    private var sizeOpenAttempts = 0
    private var sizeTarget: Int? = null
    private var scanSize: Int? = null
    private val collected = mutableListOf<Map<String,String>>()
    private var profilesPrepared = false
    private var deferredVerification = false
    private var lessonDigest = ""
    private var fatal = false
    private fun learningRecords(rows: List<LearningRecord>) = rows.filter { it.kind != "profile-use" }
    suspend fun prepareProfiles() {
        if (profilesPrepared || fatal) return
        try {
            val profiles = repository.accounts().first().filter { it.key.startsWith("sagarmeinyog:") }
            val lastUsed = profiles.associate { account -> account.key to
                (repository.records(account.key).firstOrNull { it.key == "profile-last-used" }?.observedAt ?: account.checkedAt) }
            request = request?.copy(profiles = profiles.sortedWith(
                compareByDescending<LearningAccount> { lastUsed[it.key] ?: 0 }.thenBy { it.displayName.lowercase() }), loading = false)
            profilesPrepared = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fatal = true; request = null; detail = "Could not load saved profiles. Check local storage and restart." }
    }
    suspend fun confirm(requestToken: String, localName: String, now: Long = 0): Boolean {
        val current = request?.takeIf { it.token == requestToken && !it.loading } ?: return false
        val name = localName.trim()
        if (name.isEmpty()) return false
        val existing = current.profiles.singleOrNull { it.key == name }
            ?: current.profiles.singleOrNull { it.key == "sagarmeinyog:" + CourseAnswerBank.normalize(name) }
        if (existing == null && name.length > 120) return false
        val selected = existing ?: LearningAccount("sagarmeinyog:" + CourseAnswerBank.normalize(name), name, 0)
        try {
            val rows = repository.records(selected.key)
            repository.save(selected, listOf(LearningRecord(selected.key, "profile-last-used", "", "profile-use",
                "Last selected profile", "selected", observedAt = now)))
            profile = selected
            summary = CourseProgressSummary(selected.key, selected.displayName, selected.checkedAt, learningRecords(rows))
            // Local progress is the source of truth. Never send the browser to the
            // website progress table just because this profile has not been checked.
            phase = "ready"
            request = null
            detail = "Using progress profile ${selected.displayName}."
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fatal = true; detail = "Could not save the selected profile. Check local storage and restart."; return false }
    }
    fun requireSync() {
        if (!fatal) {
            phase = "ready"
            detail = profile?.displayName?.let { "Saving local progress for $it automatically." }
                ?: "Local progress is saved automatically as videos and assessments finish."
        }
    }
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
    suspend fun capture(snapshot: CourseSnapshot, runner: CourseAutomationSession, now: Long) {
        val account = profile ?: return
        if (fatal || request != null || !snapshot.top) return
        val updates = runner.pendingCompletions.values.toList()
        val records = updates.map { value -> LearningRecord(account.key, value.key, value.course, value.kind,
            value.title, "completed", details = buildString {
                append(value.details)
                if (value.durationMillis > 0) append("\ndurationMillis=").append(value.durationMillis)
            }, observedAt = now) }.toMutableList()
        val result = snapshot.assessment
        if (result?.result in setOf("passed", "failed") && !result?.module.isNullOrBlank()) {
            val path = snapshot.path.split('/').take(4).joinToString("/")
            val stats = runner.answerStats(result!!.module)
            val details = buildString {
                append(path)
                append("\nscore=").append(result.score ?: "")
                append("\nautomaticAnswers=").append(stats.automatic)
                append("\nmanualAnswers=").append(stats.manual)
                append("\ndurationMillis=").append(runner.assessmentDuration(result.module))
            }
            records.add(LearningRecord(account.key, "assessment-result:$path:${snapshot.document}:${result!!.result}",
                result.module, "assessment", "Course Assessment", result.result!!, score = result.score, details = details, observedAt = now))
        }
        val previous = summary?.records.orEmpty().associateBy { it.key }
        val changed = records.filter { row -> previous[row.key]?.let { it.status == row.status && it.details == row.details && it.course == row.course } != true }
        if (changed.isEmpty()) { updates.forEach { runner.pendingCompletions.remove(it.key) }; return }
        try {
            repository.save(account, changed)
            hasLocalChanges = true
            summary = CourseProgressSummary(account.key, account.displayName, account.checkedAt, learningRecords(repository.records(account.key)))
            updates.forEach { runner.pendingCompletions.remove(it.key) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fatal = true; action = null; detail = "Could not save completed course progress. Check local storage before continuing." }
    }

    suspend fun process(snapshot: CourseSnapshot, now: Long, learningActionPending: Boolean = false): Boolean {
        // Progress is recorded from the live course snapshots in capture(). The
        // website's /dashboard/progress table is not authoritative and can contain
        // rows the extension cannot safely classify, so it must never block course
        // playback or certificate handling.
        if (fatal) return true
        if (request != null) prepareProfiles()
        if (fatal) return true
        if (request != null) return true
        return false
        /*
        if (!snapshot.top) return phase != "ready"
        if (fatal) return true
        if (snapshot.session != token || !snapshot.ready || snapshot.truncated) { identityMissing = true; detail = "Waiting for a complete page to check progress."; return true }
        prepareProfiles()
        if (fatal) return true
        if (profile == null) {
            detail = "Choose a saved progress profile or create one in the app. Use the profile for the account signed into Chrome."
            return true
        }
        identityMissing = false
        if (learningActionPending) return false
        if (action != null) {
            if (now - pendingAt > 15_000) { fatal = true; action = null; detail = "Progress navigation was not confirmed. Stop and restart to retry." }
            return true
        }
        if (now < nextAt && phase != "ready") return true
        val account = profile!!
        val isProgress = snapshot.path.trimEnd('/') == "/dashboard/progress"
        // A selected local profile is sufficient on test pages, which omit the account header.
        // Defer the initial website import instead of navigating away from an active attempt.
        // Keep any assessment page in place during the initial profile handoff. Besides an
        // active attempt, a completed result page can require its certificate to be downloaded
        // before it is safe to navigate away to website progress.
        if (phase != "ready" && snapshot.assessment != null) {
            phase = "ready"
            deferredVerification = true
        }
        if (deferredVerification && snapshot.assessment == null && snapshot.path in setOf("/dashboard", "/dashboard/courses", "/dashboard/progress")) {
            deferredVerification = false
            requireSync()
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
                    try {
                        val previous = summary?.records.orEmpty().associateBy { it.key }
                        val safe = observations.filterNot { previous[it.key]?.status == "completed" && it.status != "completed" }
                        repository.save(profile!!, safe)
                        summary = CourseProgressSummary(account.key, account.displayName, profile!!.checkedAt, learningRecords(repository.records(account.key)))
                        lessonDigest = digest
                    }
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
            } else if (sizeOpenAttempts < 3) {
                sizeOpenAttempts++
                click(snapshot,table.pageSizeControl,now,"Checking the largest available Rows per page option.",kind="progressPageSize")
                return true
            } else {
                fatal = true
                detail = "Rows per page did not stay open after three tries. Stop the routine, check the page in Chrome, then restart. Saved progress is unchanged."
                return true
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
            hasLocalChanges = false
            summary = CourseProgressSummary(account.key,account.displayName,now,learningRecords(repository.records(account.key)))
            profile = checked
            phase = "return"
            detail = "Saved ${records.size} assessment and quiz attempts for ${account.displayName}."
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fatal = true; detail = "Could not save progress. Check local storage and restart. Courses remain paused." }
        return true
        */
    }
}
