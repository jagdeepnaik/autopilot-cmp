package com.jagdeep.home.ui.util

import com.jagdeep.home.domain.*
import com.jagdeep.persistence.room.learning.LearningRecord
import kotlinx.serialization.json.Json

internal fun dgsHistoryText(rows:List<LearningRecord>):String = runCatching {
    val json=Json {ignoreUnknownKeys=true}
    val sessions=rows.filter {it.kind=="dgs-study"}.map {json.decodeFromString(DgsStudySession.serializer(),it.details)}.distinctBy {it.id}
    val first=sessions.minByOrNull {it.startedAt} ?: return "No saved study sessions."
    val latest=sessions.maxOf {it.updatedAt}
    val total=sessions.sumOf {it.activeMillis}
    val pages=rows.filter {it.kind=="dgs-page"}.map {json.decodeFromString(DgsPageProgress.serializer(),it.details)}.groupBy {it.id}
    val videos=rows.filter {it.kind=="dgs-video"}.map {json.decodeFromString(DgsVideoProgress.serializer(),it.details)}.groupBy {it.page+":"+it.id}
    "${first.courseName}\nStudy time: ${total/3_600_000} h ${total/60_000%60} min ${total/1000%60} sec across ${sessions.size} sessions"+
        "\nPages read: ${pages.count {it.value.any {p->p.read}}} / ${pages.size} observed · Videos ended: ${videos.count {it.value.any {v->v.ended}}} / ${videos.size} observed"+
        "\nRequired: ${first.requiredHours} h · Initial credit: ${first.baselineHours} h"+
        "\nFirst started: ${progressCheckedLabel(first.startedAt)}\nLast studied: ${progressCheckedLabel(latest)}"
}.getOrElse {"Could not read this saved course history. Restart the routine to check storage."}
