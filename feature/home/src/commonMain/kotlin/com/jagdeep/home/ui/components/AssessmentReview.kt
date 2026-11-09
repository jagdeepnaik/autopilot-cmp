package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jagdeep.home.domain.AssessmentFailureRequest

@Composable
internal fun AssessmentReview(
    failure: AssessmentFailureRequest,
    saving: Boolean,
    message: String?,
    onSave: (String, Map<String, String>) -> Unit,
) {
    var open by remember(failure.token) { mutableStateOf(false) }
    var confirm by remember(failure.token) { mutableStateOf(false) }
    var choices by remember(failure.token) { mutableStateOf<Map<String, String>>(emptyMap()) }
    TextButton(enabled = failure.questions.isNotEmpty(), onClick = { open = true }) {
        Text("Review and correct answers")
    }
    if (open) AlertDialog(
        onDismissRequest = { if (!saving) open = false },
        title = { Text("Review ${failure.module}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The score does not identify wrong answers. Choose only corrections you have verified.")
                failure.questions.forEach { question ->
                    Text(question.question, style = MaterialTheme.typography.titleSmall)
                    question.selectedAnswer?.let { Text("Attempt answer: $it", style = MaterialTheme.typography.bodySmall) }
                    question.options.forEach { option ->
                        TextButton(enabled = !saving && question.options.count { it == option } == 1,
                            onClick = { choices = choices + (question.id to option) }) {
                            Text((if (choices[question.id] == option) "✓ " else "") + option)
                        }
                    }
                    HorizontalDivider()
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && choices.isNotEmpty(), onClick = { confirm = true }) { Text("Review changes") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { open = false }) { Text("Close") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { if (!saving) confirm = false },
        title = { Text("Save reviewed corrections?") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (id, answer) ->
                Text(failure.questions.first { it.id == id }.question)
                Text("New saved answer: $answer", Modifier.padding(bottom = 12.dp))
            }
            Text("These answers will replace matching saved answers for future attempts.")
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            onSave(failure.token, choices)
            confirm = false
            choices = emptyMap()
        }) { Text("Approve and save") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Back to review") } },
    )
}
