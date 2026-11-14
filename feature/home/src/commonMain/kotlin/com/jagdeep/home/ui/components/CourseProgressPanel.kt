package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jagdeep.home.domain.*
import com.jagdeep.persistence.room.learning.LearningAccount

@Composable
internal fun CourseProgressPanel(request: ProgressProfileRequest?, summary: CourseProgressSummary?, confirm: (String,String)->Unit) {
    if (request != null) {
        var selected by remember(request.token, request.profiles) { mutableStateOf(request.profiles.firstOrNull()?.key) }
        var name by remember(request.token) { mutableStateOf("") }
        AlertDialog(onDismissRequest = {}, title = { Text("Choose progress profile") }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose the profile for the account signed into Chrome. The website does not show a username on assessment pages, so the app uses your choice throughout this run.")
                if (request.loading) Text("Loading saved profiles…")
                request.profiles.forEachIndexed { index, profile ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { selected = profile.key }) {
                        RadioButton(selected = selected == profile.key, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.displayName)
                            Text(profile.key.removePrefix("sagarmeinyog:") + if (index == 0) " · Most recent" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(enabled = !request.loading, onClick = { selected = null }) { Text("Create new profile") }
                if (selected == null && !request.loading) {
                    OutlinedTextField(value = name, onValueChange = { if(it.length <= 120) name = it }, label = { Text("Profile name") }, singleLine = true)
                }
                Text("Select the correct account before continuing. Use a distinct profile for each person. Never enter a password here.")
            }
        }, confirmButton = { TextButton(enabled = !request.loading && (selected != null || name.isNotBlank()), onClick = { confirm(request.token, selected ?: name.trim()) }) { Text("Use this profile") } })
    }
    summary?.let { Text("Progress saved for ${it.displayName}: ${it.records.count { row -> row.kind == "assessment" && row.status == "passed" }} passed assessment attempts.", style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun CourseProgressHistory(accounts: List<LearningAccount>, summary: CourseProgressSummary?, allSummaries: List<CourseProgressSummary>, error: String?, load: (String)->Unit, loadAll: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }) { Text("Saved progress by user") }
    if(open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Saved learning progress") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(accounts.isEmpty()) Text("No saved profiles yet. Start a course routine to create one.")
            if (accounts.isNotEmpty()) TextButton(onClick = loadAll) { Text("Load all profiles") }
            allSummaries.forEach { history ->
                HorizontalDivider()
                Text(history.displayName, style = MaterialTheme.typography.titleMedium)
                ProgressModuleOverview(history)
            }
            accounts.forEach { profile -> TextButton(onClick = { selected = profile.key; load(profile.key) }) { Text(if(profile.key.startsWith("dgs:")) profile.displayName else profile.displayName + " · " + profile.key.removePrefix("sagarmeinyog:")) } }
            error?.let { Text(it) }
            summary?.takeIf { it.account == selected }?.let { history ->
                HorizontalDivider()
                Text("Module overview", style = MaterialTheme.typography.titleMedium)
                ProgressModuleOverview(history)
                HorizontalDivider()
                Text("Details", style = MaterialTheme.typography.titleMedium)
                Text(history.displayName, style = MaterialTheme.typography.titleMedium)
                Text("Last progress check: ${com.jagdeep.home.ui.util.progressCheckedLabel(history.checkedAt)}")
                history.records.groupBy { it.course }.forEach { (course, rows) ->
                    HorizontalDivider()
                    if(rows.any {it.kind=="dgs-study"}) {
                        Text(com.jagdeep.home.ui.util.dgsHistoryText(rows),style=MaterialTheme.typography.bodySmall)
                    } else {
                    Text(course, style = MaterialTheme.typography.titleSmall)
                    rows.forEach { row -> Text(buildString {
                        append("${row.kind}: ${row.status}")
                        row.score?.let { append(" · $it%") }
                        append("\n${row.title}")
                        if(row.kind != "lesson") append("\n${row.details}")
                    }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
private fun ProgressModuleOverview(history: CourseProgressSummary) {
    val modules = SAGAR_MEIN_YOG_MODULES.map { module ->
        val normalized = CourseAnswerBank.normalize(module)
        val video = history.records.filter { it.kind == "module-videos" && CourseAnswerBank.normalize(it.course) == normalized }.maxByOrNull { it.observedAt }
        val assessment = history.records.filter { it.kind == "assessment" && CourseAnswerBank.normalize(it.course) == normalized }.maxByOrNull { it.observedAt }
        ModuleOverview(
            name = module,
            videos = video?.status == "completed",
            assessment = assessment?.status == "passed",
            durationMillis = detailMetric(video?.details.orEmpty(), "durationMillis") + detailMetric(assessment?.details.orEmpty(), "durationMillis"),
        )
    }
    Text(
        "${modules.count { it.videos && it.assessment }} / ${modules.size} modules complete · ${formatDuration(modules.sumOf { it.durationMillis })}",
        style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        modules.forEach { module ->
            Text(
                "${module.name} · videos ${if (module.videos) "complete" else "incomplete"} · assessment ${if (module.assessment) "passed" else "not passed"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class ModuleOverview(val name: String, val videos: Boolean, val assessment: Boolean, val durationMillis: Long)

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
}
