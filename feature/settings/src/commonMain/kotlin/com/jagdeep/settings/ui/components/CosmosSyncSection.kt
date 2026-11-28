package com.jagdeep.settings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jagdeep.settings.ui.util.cosmosPendingText
import com.jagdeep.settings.ui.util.cosmosStatusText
import com.jagdeep.settings.ui.util.runLogsText
import com.jagdeep.shared.cosmos.CosmosSessionEnd
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncState
import kotlinx.coroutines.delay

/**
 * Settings rows for the opt-in Cosmos Q&A sync. Passwords live only in these fields while typed: they
 * are handed to the request and then cleared, never saved. While this computer waits for the admin's
 * approval the sign-in password stays in its field (on screen only) so Try again can resend it.
 */
@Composable
internal fun CosmosSyncSection(
    state: CosmosSyncState,
    signingIn: Boolean,
    signInProblem: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    runLogsAvailable: Boolean = false,
    onOpenRunLogs: () -> Unit = {},
    machinePending: Boolean = false,
    changingPassword: Boolean = false,
    passwordProblem: String? = null,
    onChangePassword: (current: String, new: String) -> Unit = { _, _ -> },
    openingCosmos: Boolean = false,
    onOpenCosmos: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenMachines: () -> Unit = {},
) {
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (!state.available) {
        ListItem(colors = transparent, headlineContent = { Text("Cosmos sync") },
            supportingContent = { Text("Not available on this computer. Sync needs the system's secure storage to keep your session safe.") })
        RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
        return
    }
    val account = state.account
    if (account == null) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val submit = { onSignIn(email, password) }
        // Cleared once a request finishes, unless Try again will need it.
        LaunchedEffect(signingIn, machinePending) { if (!signingIn && !machinePending) password = "" }
        ListItem(colors = transparent, headlineContent = { Text("Cosmos sync") }, supportingContent = {
            Text(when {
                state.sessionEnd == CosmosSessionEnd.MachineRevoked -> "The admin removed this computer from your Cosmos account, so you were signed out. Ask the admin to approve it again."
                state.sessionEnd == CosmosSessionEnd.AccountDisabled -> "Your Cosmos account was disabled, so you were signed out. Ask the admin to enable it."
                state.signInExpired -> "Your Cosmos session ended. Sign in again to keep syncing."
                else -> "Off. Sign in to get answers approved on Cosmos and share the answers you confirm. Only questions, options, chosen answers and an anonymous ID for this computer are sent."
            })
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
                    Text(when { signingIn -> "Signing in…"; machinePending -> "Try again"; else -> "Sign in" })
                }
            }
        }
        RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
        return
    }
    if (state.passwordChangeRequired) {
        PasswordChangeRows(account.name, changingPassword, passwordProblem, onChangePassword, onSignOut)
        return
    }
    // Re-reads the clock every minute so "5 minutes ago" stays true while Settings is open.
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(state) { while (true) { clock = now(); delay(60_000) } }
    ListItem(colors = transparent, headlineContent = { Text("Signed in to Cosmos as ${account.name}") },
        supportingContent = { Text(cosmosStatusText(state, clock), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        trailingContent = { TextButton(onClick = onSignOut) { Text("Sign out") } })
    ListItem(colors = transparent, headlineContent = { Text("Your answers") },
        supportingContent = { Text(cosmosPendingText(state.pendingUploads)) },
        trailingContent = { TextButton(onClick = onSyncNow, enabled = !state.syncing) { Text(if (state.syncing) "Syncing…" else "Sync now") } })
    ListItem(colors = transparent, headlineContent = { Text("Cosmos website") },
        supportingContent = { Text("Signs your default browser in to Cosmos on this computer.") },
        trailingContent = { TextButton(onClick = onOpenCosmos, enabled = !openingCosmos) { Text(if (openingCosmos) "Opening…" else "Open Cosmos") } })
    if (account.isAdmin) {
        ListItem(modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open accounts", onClick = onOpenAccounts), colors = transparent,
            headlineContent = { Text("Accounts") }, supportingContent = { Text("Create accounts, set machine limits, disable accounts and reset passwords.") },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) })
        ListItem(modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open machines", onClick = onOpenMachines), colors = transparent,
            headlineContent = { Text("Machines") }, supportingContent = { Text("Approve or revoke the computers people sign in from.") },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) })
    }
    RunLogsRow(state, runLogsAvailable, onOpenRunLogs)
}

/** The first sign-in with a temporary password: choose a new one before anything syncs. */
@Composable
private fun PasswordChangeRows(name: String, changing: Boolean, problem: String?, onChange: (String, String) -> Unit, onSignOut: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    val canSubmit = !changing && current.isNotEmpty() && new.length >= CosmosSync.MIN_PASSWORD_LENGTH
    val submit = { if (canSubmit) onChange(current, new) }
    // Both fields are emptied once the request finishes, whatever its result.
    LaunchedEffect(changing) { if (!changing) { current = ""; new = "" } }
    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Choose a new Cosmos password") },
        supportingContent = { Text("Signed in as $name with a temporary password. Choose your own password to start syncing.") },
        trailingContent = { TextButton(onClick = onSignOut) { Text("Sign out") } })
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth(), label = { Text("Current password") }, singleLine = true, enabled = !changing,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
        OutlinedTextField(new, { new = it }, Modifier.fillMaxWidth(), label = { Text("New password") }, singleLine = true, enabled = !changing,
            supportingText = { Text("At least ${CosmosSync.MIN_PASSWORD_LENGTH} characters.") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }))
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(Modifier.padding(bottom = 8.dp)) {
            Button(onClick = submit, enabled = canSubmit) { Text(if (changing) "Changing…" else "Change password") }
        }
    }
}

/** The routine run logs kept on this computer, and the folder they're in. */
@Composable
private fun RunLogsRow(state: CosmosSyncState, available: Boolean, onOpen: () -> Unit) {
    if (!available) return
    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Routine run logs") },
        supportingContent = { Text(runLogsText(state.runLogsWaiting, state.account != null, state.available)) },
        trailingContent = { TextButton(onClick = onOpen) { Text("Open logs folder") } })
}
