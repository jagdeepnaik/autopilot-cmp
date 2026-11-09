package com.jagdeep.home.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CourseWireEvent(
    val type: String,
    val session: String = "",
    val snapshot: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val chunk: String = "",
    val request: Long = 0,
    val success: Boolean = false,
)

/** Bounded, ordered assembly. A partial, duplicated or mixed inventory never triggers an action. */
internal class CourseSnapshotAssembler {
    private data class Pending(val session: String, val total: Int, val chunks: MutableList<String>)
    private val pending = mutableMapOf<String, Pending>()
    private val json = Json { ignoreUnknownKeys = true }
    fun clear() = pending.clear()
    fun accept(event: CourseWireEvent): CourseSnapshot? {
        if (event.total !in 1..2000 || event.index !in 0 until event.total || event.chunk.length > 1000) return null
        if (event.index == 0) {
            if (pending.size >= 8) pending.remove(pending.keys.first())
            pending[event.snapshot] = Pending(event.session, event.total, mutableListOf())
        }
        val entry = pending[event.snapshot] ?: return null
        if (entry.session != event.session || entry.total != event.total || entry.chunks.size != event.index) {
            pending.remove(event.snapshot)
            return null
        }
        entry.chunks.add(event.chunk)
        if (entry.chunks.size != entry.total) return null
        pending.remove(event.snapshot)
        return runCatching { json.decodeFromString(CourseSnapshot.serializer(), entry.chunks.joinToString("")) }
            .getOrNull()?.takeIf { it.session == event.session }
    }
}
