package com.jagdeep.settings.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jagdeep.settings.ui.util.compassPendingText
import com.jagdeep.settings.ui.util.compassStatusText
import com.jagdeep.settings.ui.util.runLogsText
import com.jagdeep.shared.compass.CompassSyncState
import kotlinx.coroutines.delay

/**
 * Settings rows for the opt-in Compass Q&A sync. The password lives only in this field while typed:
 * it is handed to the sign-in request and then cleared, never saved.
 */
@Composable
internal fun CompassSyncSection(
    state: CompassSyncState,
    signingIn: Boolean,
    signInProblem: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    runLogsAvailable: Boolean = false,
    onOpenRunLogs: () -> Unit = {},
) {
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (!state.available) {
        ListItem(colors = transparent, headlineContent = { Text("Compass sync") },
            supportingContent = { Text("Not available on this computer. Sync needs the system's secure storage to keep your session safe.") })
        RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
        return
    }
    val account = state.account
    if (account == null) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val submit = { onSignIn(email, password); password = "" }
        ListItem(colors = transparent, headlineContent = { Text("Compass sync") }, supportingContent = {
            Text(
                if (state.signInExpired) "Your Compass session ended. Sign in again to keep syncing."
                else "Off. Sign in to get answers approved on Compass and share the answers you confirm. Only questions, options and chosen answers are sent.",
            )
        })
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, enabled = !signingIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, enabled = !signingIn,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!signingIn) submit() }))
            signInProblem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.padding(bottom = 8.dp)) {
                Button(onClick = submit, enabled = !signingIn && email.isNotBlank() && password.isNotEmpty()) {
                    Text(if (signingIn) "Signing in…" else "Sign in")
                }
            }
        }
        RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
        return
    }
    // Re-reads the clock every minute so "5 minutes ago" stays true while Settings is open.
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(state) { while (true) { clock = now(); delay(60_000) } }
    ListItem(colors = transparent, headlineContent = { Text("Signed in to Compass as ${account.name}") },
        supportingContent = { Text(compassStatusText(state, clock), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        trailingContent = { TextButton(onClick = onSignOut) { Text("Sign out") } })
    ListItem(colors = transparent, headlineContent = { Text("Your answers") },
        supportingContent = { Text(compassPendingText(state.pendingUploads)) },
        trailingContent = { TextButton(onClick = onSyncNow, enabled = !state.syncing) { Text(if (state.syncing) "Syncing…" else "Sync now") } })
    RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
}

/** The routine run logs kept on this computer, and the folder they're in. */
@Composable
private fun RunLogsRow(state: CompassSyncState, available: Boolean, onOpen: () -> Unit) {
    if (!available) return
    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Routine run logs") },
        supportingContent = { Text(runLogsText(state.runLogsWaiting, state.account != null, state.available)) },
        trailingContent = { TextButton(onClick = onOpen) { Text("Open logs folder") } })
}
