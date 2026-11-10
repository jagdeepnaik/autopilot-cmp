package com.jagdeep.settings.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jagdeep.shared.routines.TransferRoutine

@Composable
internal fun RoutineTransferControls(routines: List<TransferRoutine>, busy: Boolean, onImport: () -> Unit, onExport: (Set<String>) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    ListItem(modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onImport),
        headlineContent = { Text("Import routines") }, supportingContent = { Text("Add copies from an .autopilot.json file.") },
        leadingContent = { Icon(Icons.Default.FileOpen, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    ListItem(modifier = Modifier.clickable(enabled = !busy && routines.isNotEmpty(), role = Role.Button) { selected = routines.map { it.id }.toSet(); choosing = true },
        headlineContent = { Text("Export routines") }, supportingContent = { Text("Choose routines to share or back up.") },
        leadingContent = { Icon(Icons.Default.IosShare, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    if (busy) Text("Working with routine files…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (choosing) AlertDialog(
        onDismissRequest = { if (!busy) choosing = false },
        title = { Text("Select routines to export") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(routines, key = { it.id }) { routine ->
                ListItem(modifier = Modifier.toggleable(value = routine.id in selected, role = Role.Checkbox) {
                    selected = if(it) selected + routine.id else selected - routine.id
                }, headlineContent = { Text(routine.name) }, leadingContent = { Checkbox(checked = routine.id in selected, onCheckedChange = null) })
            }
        } },
        confirmButton = { TextButton(enabled = !busy && routines.any { it.id in selected }, onClick = {
            choosing = false
            onExport(selected.intersect(routines.map { it.id }.toSet()))
        }) { Text("Export selected") } },
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
}
