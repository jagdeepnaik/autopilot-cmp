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
        var name by remember(request.token) { mutableStateOf(request.displayName) }
        AlertDialog(onDismissRequest = {}, title = { Text("Confirm progress profile") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Signed in as ${request.displayName}. Progress will be stored locally under the profile below.")
                OutlinedTextField(value = name, onValueChange = { if(it.length <= 120) name = it }, label = { Text("Local profile name") }, singleLine = true)
                Text("Reuse the same profile for this account. If two users share a name, give each a different profile name. Never enter a password here.")
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { confirm(request.token,name) }) { Text("Use this profile") } })
    }
    summary?.let { Text("Progress saved for ${it.displayName}: ${it.records.count { row -> row.kind == "assessment" && row.status == "passed" }} passed assessment attempts.", style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun CourseProgressHistory(accounts: List<LearningAccount>, summary: CourseProgressSummary?, error: String?, load: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }) { Text("Saved progress by user") }
    if(open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Saved learning progress") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(accounts.isEmpty()) Text("No saved profiles yet. Start a course routine to check progress.")
            accounts.forEach { profile -> TextButton(onClick = { selected = profile.key; load(profile.key) }) { Text(profile.displayName + " · " + profile.key.removePrefix("sagarmeinyog:")) } }
            error?.let { Text(it) }
            summary?.takeIf { it.account == selected }?.let { history ->
                Text(history.displayName, style = MaterialTheme.typography.titleMedium)
                Text("Last progress check: ${com.jagdeep.home.ui.util.progressCheckedLabel(history.checkedAt)}")
                history.records.groupBy { it.course }.forEach { (course, rows) ->
                    HorizontalDivider()
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
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
