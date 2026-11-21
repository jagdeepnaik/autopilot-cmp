package com.jagdeep.home.domain

import kotlinx.serialization.json.Json

/** Same bounded ordered transport pattern as course snapshots; isolated from SMY's assembler. */
internal class MarineSamrajReadingAssembler {
    private var snapshot = ""
    private var session = ""
    private var total = 0
    private val chunks = mutableListOf<String>()
    private val json = Json { ignoreUnknownKeys = true }
    fun clear() { snapshot = ""; session = ""; total = 0; chunks.clear() }
    fun accept(event: CourseWireEvent): MarineSamrajReading? {
        if (event.type != "marineSamrajChunk" || event.total !in 1..1000 || event.index !in 0 until event.total || event.chunk.length > 1000) { clear(); return null }
        if (event.index == 0) { clear(); snapshot = event.snapshot; session = event.session; total = event.total }
        if (event.snapshot != snapshot || event.session != session || event.total != total || event.index != chunks.size) { clear(); return null }
        chunks += event.chunk
        if (chunks.size != total) return null
        val payload = chunks.joinToString("")
        clear()
        return runCatching { json.decodeFromString(MarineSamrajReading.serializer(), payload) }.getOrNull()
            ?.takeIf { it.session == event.session && it.links.size <= 1000 && it.controls.size <= 250 }
    }
}
