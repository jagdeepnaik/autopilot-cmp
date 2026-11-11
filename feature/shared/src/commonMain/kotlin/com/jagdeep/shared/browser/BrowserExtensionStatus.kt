package com.jagdeep.shared.browser

/** The version bundled with this app and the version actually reported by Chrome. */
data class BrowserExtensionStatus(
    val latestVersion: String? = null,
    val connectedVersion: String? = null,
    val folderProblem: String? = null,
) {
    val warning: String? get() = when {
        folderProblem != null -> folderProblem
        latestVersion == null -> null
        connectedVersion == null -> "Chrome hasn't reported its extension version. Reload AutoPilot Page Helper in chrome://extensions, then refresh the course tab. Required version: $latestVersion."
        connectedVersion != latestVersion -> "Chrome extension $connectedVersion is out of date for this app. Reload AutoPilot Page Helper to $latestVersion in chrome://extensions, then refresh the course tab."
        else -> null
    }
}
