package com.jagdeep.home.domain

/** Monotonic, progress-based watchdog. Heartbeats and elapsed-time labels are not progress. */
class RoutineInactivityMonitor(private val intervalMillis: Long = 5 * 60_000L) {
    private var marker: String? = null
    private var progressAt: Long? = null
    private var alertedAt: Long? = null
    var isStalled: Boolean = false
        private set

    fun observe(active: Boolean, progressMarker: String, now: Long, soundsEnabled: Boolean): Boolean {
        if (!active) {
            marker = null; progressAt = null; alertedAt = null; isStalled = false
            return false
        }
        if (marker != progressMarker || progressAt == null) {
            marker = progressMarker; progressAt = now; alertedAt = null; isStalled = false
            return false
        }
        isStalled = now - progressAt!! >= intervalMillis
        if (!isStalled || !soundsEnabled || alertedAt?.let { now - it < intervalMillis } == true) return false
        alertedAt = now
        return true
    }
}

internal fun CourseSnapshot.progressMarker(): String = buildString {
    append(path); append('|'); append(document)
    elements.filter { it.visible }.forEach { element ->
        when {
            element.tag == "video" && !element.decorative -> append("|video:${element.mediaPosition?.toLong()}:${element.paused}:${element.ended}")
            element.expanded != null -> append("|chapter:${element.label}:${element.expanded}")
            element.href != null -> append("|link:${element.href}:${element.label}")
            element.tag in setOf("h1", "h2", "h3", "h4") && (element.label.startsWith("Part", true) || element.label.equals("Video Completed!", true)) -> append("|heading:${element.label}")
        }
    }
    assessment?.let { value ->
        append("|assessment:${value.module}:${value.result}")
        value.questions.forEach { question ->
            append('|'); append(question.text)
            question.options.forEach { append("|${it.text}:${it.selected}") }
        }
    }
    progress?.let { table ->
        append("|progress:${table.page}:${table.pages}:${table.total}:${table.pageSize}:${table.pageSizeOpen}")
        table.rows.forEach { row -> append(row.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}" }) }
    }
}
