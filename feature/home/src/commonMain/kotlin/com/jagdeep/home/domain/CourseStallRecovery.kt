package com.jagdeep.home.domain

/**
 * Does what the user did by hand when a Sagar Mein Yog run stopped moving: reload the course tab once,
 * wait for the new page, and carry on from the step it shows.
 *
 * Bounded: one reload per stall. If the new page also makes no progress, it gives up and the normal
 * five-minute stall alert takes over; a run reloads at most [maxReloads] times in all. A wait for the
 * user, a timed wait or an assessment ([CourseAutomationSession.recoveryBlocker]) is never a stall.
 *
 * Progress is any change in what the page reports ([CourseSnapshot.progressMarker]). The first page
 * after a reload only shows the page was found again; the next change shows the run is moving.
 */
internal class CourseStallRecovery(
    private val stallMillis: Long = STALL_MILLIS,
    private val reloadTimeoutMillis: Long = RELOAD_TIMEOUT_MILLIS,
    private val maxReloads: Int = MAX_RELOADS,
) {
    sealed interface Step {
        data object None : Step

        /** Ask the extension to reload the course tab for [request]. */
        data class Reload(val request: Long, val stalledMillis: Long, val reload: Int) : Step

        /** The reloaded page reported in: the run can see the page again. */
        data class PageFound(val afterMillis: Long) : Step

        /** The page moved again after a reload. */
        data class Recovered(val afterMillis: Long) : Step

        /** Recovery stopped for this stall; the stall alert takes over. [reason] is a short code. */
        data class GaveUp(val reason: String) : Step
    }

    /** The reload request the extension should act on, or 0. */
    var pendingRequest: Long = 0
        private set
    var reloads: Int = 0
        private set

    private var progressAt: Long? = null
    private var reloadAt: Long? = null
    private var pageFound = false
    private var exhausted = false
    private var lastRequest = 0L

    /** Something on the page changed. */
    fun progressed(now: Long): Step {
        progressAt = now
        exhausted = false
        val since = reloadAt ?: return Step.None
        if (pendingRequest != 0L) return Step.None // Still the old page, before the reload.
        if (!pageFound) {
            pageFound = true
            return Step.PageFound(now - since)
        }
        reloadAt = null
        pageFound = false
        return Step.Recovered(now - since)
    }

    /** Called often while the run is active. [blocked] says a stall now would be a legitimate wait. */
    fun check(now: Long, blocked: Boolean): Step {
        val lastProgress = progressAt ?: run { progressAt = now; return Step.None }
        val reloadedAt = reloadAt
        if (blocked && reloadedAt != null && pageFound) {
            // The reloaded page now waits for the user or a timer: that's a working run again.
            progressAt = now
            reloadAt = null
            pageFound = false
            return Step.Recovered(now - reloadedAt)
        }
        reloadedAt?.let { since ->
            if (now - since < reloadTimeoutMillis) return Step.None
            // The reload was sent, but the page didn't come back or didn't move: stop here.
            return giveUp(if (pendingRequest != 0L) "noReloadReply" else if (pageFound) "stillStalled" else "pageNotFound")
        }
        if (blocked) {
            progressAt = now
            return Step.None
        }
        if (exhausted || now - lastProgress < stallMillis) return Step.None
        if (reloads >= maxReloads) return giveUp("reloadLimit")
        reloads++
        lastRequest = maxOf(now, lastRequest + 1)
        pendingRequest = lastRequest
        reloadAt = now
        pageFound = false
        return Step.Reload(lastRequest, now - lastProgress, reloads)
    }

    /** The extension's answer to a reload request. [problem] is its short code when it couldn't reload. */
    fun acknowledged(request: Long, success: Boolean, problem: String?, now: Long): Step {
        if (request == 0L || request != pendingRequest) return Step.None
        pendingRequest = 0
        if (!success) return giveUp("reloadRefused:${problem ?: "unknown"}")
        reloadAt = now
        return Step.None
    }

    private fun giveUp(reason: String): Step {
        pendingRequest = 0
        reloadAt = null
        pageFound = false
        exhausted = true
        return Step.GaveUp(reason)
    }

    companion object {
        /** No page change for this long, outside a legitimate wait, counts as a stall. */
        const val STALL_MILLIS = 90_000L

        /** How long a reload may take to bring the page back and moving. */
        const val RELOAD_TIMEOUT_MILLIS = 60_000L

        const val MAX_RELOADS = 5
    }
}
