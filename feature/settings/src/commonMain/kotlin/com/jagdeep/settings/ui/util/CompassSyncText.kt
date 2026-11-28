package com.jagdeep.settings.ui.util

import com.jagdeep.shared.compass.CompassSyncOutcome
import com.jagdeep.shared.compass.CompassSyncState

/** The Compass sync status in plain words: what happened, and what happens next. */
internal fun compassStatusText(state: CompassSyncState, nowMillis: Long): String {
    if (state.syncing) return "Syncing answers with Compass…"
    val last = state.lastSyncAtMillis?.let { "Last synced ${timeAgo(nowMillis - it)}." }
    return when (state.lastOutcome) {
        null -> last ?: "Not synced yet."
        CompassSyncOutcome.Synced -> listOfNotNull(last, syncedCounts(state.lastDownloaded, state.lastSent)).joinToString(" ")
        CompassSyncOutcome.Offline -> listOfNotNull("Couldn't reach Compass. AutoPilot keeps working and tries again later.", last).joinToString(" ")
        CompassSyncOutcome.ServerTrouble -> listOfNotNull("Compass had a problem with the last sync. AutoPilot tries again later.", last).joinToString(" ")
        CompassSyncOutcome.RateLimited -> listOfNotNull("Compass asked AutoPilot to wait. It tries again later.", last).joinToString(" ")
        CompassSyncOutcome.StorageProblem -> "AutoPilot couldn't update its Q&A files. Check that the .autopilot folder in your home folder can be changed, then choose Sync now."
        CompassSyncOutcome.SignInNeeded -> "Sign in again to keep syncing."
    }
}

internal fun compassPendingText(pending: Int): String = when (pending) {
    0 -> "Every answer you confirmed has been sent."
    1 -> "1 confirmed answer is waiting to be sent."
    else -> "$pending confirmed answers are waiting to be sent."
}

/** The routine run-log line: what waits to go to Compass, or that logs stay here while signed out. */
internal fun runLogsText(waiting: Int, signedIn: Boolean, syncAvailable: Boolean): String = when {
    !syncAvailable || !signedIn -> "Run logs: every routine run is logged on this computer for 30 days. They're sent to Compass only while you're signed in."
    waiting == 0 -> "Run logs: all sent. Logs keep routine steps and timings only, never page text or answers."
    waiting == 1 -> "Run logs: 1 waiting to upload."
    else -> "Run logs: $waiting waiting to upload."
}

private fun syncedCounts(downloaded: Int, sent: Int): String = when {
    downloaded == 0 && sent == 0 -> "Everything is up to date."
    else -> "Received ${count(downloaded, "approved answer")}, sent ${count(sent, "answer")}."
}

private fun count(value: Int, noun: String) = if (value == 1) "1 $noun" else "$value ${noun}s"

internal fun timeAgo(elapsedMillis: Long): String {
    val minutes = elapsedMillis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        minutes < 24 * 60 -> (minutes / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        else -> (minutes / (24 * 60)).let { if (it == 1L) "1 day ago" else "$it days ago" }
    }
}
