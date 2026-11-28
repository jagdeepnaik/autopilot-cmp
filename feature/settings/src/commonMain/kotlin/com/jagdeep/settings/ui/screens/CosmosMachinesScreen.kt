package com.jagdeep.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jagdeep.settings.domain.CosmosMachinesViewModel
import com.jagdeep.settings.ui.navigation.RouteCosmosMachines
import com.jagdeep.settings.ui.util.machineSummary
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosMachineStatus
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState

/** The admin's computers: waiting ones first, each with Approve or Revoke. */
@Composable
fun CosmosMachinesScreen(viewModel: CosmosMachinesViewModel, onBack: () -> Unit, now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }) {
    val state by viewModel.state.collectAsState()
    val topBar = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    SideEffect { topBar.value = TopAppBarState(null) { AppBarTop("Machines", RouteCosmosMachines, onBack, {}, {}) } }
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast.show(it) } }
    var revoking by remember { mutableStateOf<CosmosAdminMachine?>(null) }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    val nowMillis = remember(state.machines) { now() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "All", CosmosMachineStatus.Pending to "Waiting", CosmosMachineStatus.Approved to "Approved", CosmosMachineStatus.Revoked to "Revoked").forEach { (status, label) ->
                        FilterChip(selected = state.status == status, onClick = { viewModel.updateStatus(status) }, label = { Text(label) })
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.actionProblem?.let { problem ->
                    ListItem(colors = transparent, headlineContent = { Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) },
                        trailingContent = { TextButton(onClick = viewModel::dismissActionProblem) { Text("Dismiss") } })
                }
                state.problem?.let { problem ->
                    ListItem(colors = transparent, headlineContent = { Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                        trailingContent = { TextButton(onClick = viewModel::reload) { Text("Try again") } })
                }
                if (!state.loading && state.problem == null && state.machines.isEmpty()) {
                    ListItem(colors = transparent, headlineContent = { Text(if (state.status == CosmosMachineStatus.Pending) "No computers are waiting" else "No computers yet") },
                        supportingContent = { Text("A computer appears here after someone signs in to Cosmos from AutoPilot on it.") })
                }
            }
            items(state.machines, key = { it.id }) { machine ->
                val busy = machine.id in state.busy
                ListItem(
                    colors = transparent,
                    headlineContent = { Text(machine.label.ifBlank { "Unnamed computer" }) },
                    supportingContent = { Text(machineSummary(machine, nowMillis)) },
                    trailingContent = {
                        when (machine.status) {
                            CosmosMachineStatus.Approved -> TextButton(onClick = { revoking = machine }, enabled = !busy) { Text("Revoke") }
                            CosmosMachineStatus.Pending -> FilledTonalButton(onClick = { viewModel.approve(machine) }, enabled = !busy) { Text(if (busy) "Approving…" else "Approve") }
                            else -> TextButton(onClick = { viewModel.approve(machine) }, enabled = !busy) { Text("Approve again") }
                        }
                    },
                )
                HorizontalDivider()
            }
            if (state.canLoadMore) item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = viewModel::loadMore) { Text("Show more (${state.machines.size} of ${state.total})") }
                }
            }
        }
    }

    revoking?.let { machine ->
        val owner = machine.accountName ?: "this account"
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${machine.label.ifBlank { "this computer" }}?") },
            text = { Text("AutoPilot and the browser on this computer are signed out of $owner at once. Approve it again to let it back in.") },
            confirmButton = { TextButton(onClick = { revoking = null; viewModel.revoke(machine) }) { Text("Revoke", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}
