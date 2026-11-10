package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jagdeep.home.domain.DgsCourseChoice

@Composable
internal fun DgsCourseDialog(courses: List<DgsCourseChoice>, onSelect: (String) -> Unit, onStop: () -> Unit) {
    val available=courses.filter { it.available }
    var selected by remember(available) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Choose your DGS course") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select a course number. The app opens that course and reads its e-book. Assessments stay manual.")
                Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    available.forEachIndexed { index, course ->
                        Row(Modifier.fillMaxWidth().selectable(selected==course.id,enabled=course.available,role=Role.RadioButton,onClick={selected=course.id}).padding(vertical=10.dp)) {
                            RadioButton(selected==course.id,onClick=null,enabled=course.available)
                            Column(Modifier.weight(1f).padding(start=8.dp)) {
                                Text("${index+1}. ${course.label}",style=MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(course.code.takeIf { it.isNotBlank() },if(course.available) "Available" else "Unavailable on DGS").joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick={selected?.let(onSelect)},enabled=available.any { it.id==selected && it.available }) { Text("Open course") } },
        dismissButton = { TextButton(onClick=onStop) { Text("Stop routine") } },
    )
}
