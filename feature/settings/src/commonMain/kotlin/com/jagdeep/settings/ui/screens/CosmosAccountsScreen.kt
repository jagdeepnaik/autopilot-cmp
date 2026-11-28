package com.jagdeep.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jagdeep.settings.domain.AccountForm
import com.jagdeep.settings.domain.CosmosAccountsViewModel
import com.jagdeep.settings.ui.navigation.RouteCosmosAccounts
import com.jagdeep.settings.ui.util.accountSummary
import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import kotlinx.coroutines.launch

/** The admin's Cosmos accounts: one list of rows, with dialogs for creating, editing and confirming. */
@Composable
fun CosmosAccountsScreen(viewModel: CosmosAccountsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBar = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    SideEffect { topBar.value = TopAppBarState(null) { AppBarTop("Accounts", RouteCosmosAccounts, onBack, {}, {}) } }
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast.show(it) } }
    var confirm by remember { mutableStateOf<Pair<CosmosAdminAccount, AccountAction>?>(null) }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(state.search, viewModel::updateSearch, Modifier.weight(1f), label = { Text("Search by name or email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                        Button(onClick = viewModel::startCreate) { Text("New account") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "All", CosmosAccountStatus.Active to "Active", CosmosAccountStatus.Disabled to "Disabled").forEach { (status, label) ->
                            FilterChip(selected = state.status == status, onClick = { viewModel.updateStatus(status) }, label = { Text(label) })
                        }
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.problem?.let { problem ->
                    ListItem(colors = transparent, headlineContent = { Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                        trailingContent = { TextButton(onClick = { viewModel.reload() }) { Text("Try again") } })
                }
                if (!state.loading && state.problem == null && state.accounts.isEmpty()) {
                    ListItem(colors = transparent, headlineContent = { Text(if (state.search.isBlank() && state.status == null) "No accounts yet" else "No accounts match") },
                        supportingContent = { Text("Create an account for each person who uses AutoPilot with Cosmos.") })
                }
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountRow(account, busy = account.id in state.busy, onEdit = { viewModel.startEdit(account) },
                    onAction = { action -> if (action == AccountAction.Enable) viewModel.setEnabled(account, true) else confirm = account to action })
                HorizontalDivider()
            }
            if (state.canLoadMore) item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = viewModel::loadMore) { Text("Show more (${state.accounts.size} of ${state.total})") }
                }
            }
        }
    }

    state.form?.let { form -> AccountFormDialog(form, state.formProblem, state.saving, viewModel::updateForm, viewModel::saveForm, viewModel::cancelForm) }
    confirm?.let { (account, action) -> ConfirmDialog(account, action, onConfirm = {
        confirm = null
        if (action == AccountAction.Disable) viewModel.setEnabled(account, false) else viewModel.resetPassword(account)
    }, onDismiss = { confirm = null }) }
    state.shownPassword?.let { shown ->
        val clipboard = LocalClipboardManager.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (shown.isNewAccount) "Account created" else "Password reset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Give this temporary password to ${shown.accountName}. It's shown only now; ${shown.accountName} chooses a new password at the first sign-in.")
                    SelectionContainer { Text(shown.password, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)) }
                }
            },
            confirmButton = { Button(onClick = viewModel::dismissPassword) { Text("Done") } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(shown.password)); scope.launch { toast.show("Temporary password copied.") } }) { Text("Copy") } },
        )
    }
}

internal enum class AccountAction { Disable, Enable, ResetPassword }

@Composable
private fun AccountRow(account: CosmosAdminAccount, busy: Boolean, onEdit: () -> Unit, onAction: (AccountAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(account.name) },
        supportingContent = { Text(accountSummary(account)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${account.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit name and limit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Reset password") }, onClick = { menu = false; onAction(AccountAction.ResetPassword) })
                    if (!account.isAdmin) {
                        if (account.status == CosmosAccountStatus.Disabled) DropdownMenuItem(text = { Text("Enable") }, onClick = { menu = false; onAction(AccountAction.Enable) })
                        else DropdownMenuItem(text = { Text("Disable") }, onClick = { menu = false; onAction(AccountAction.Disable) })
                    }
                }
            }
        },
    )
}

@Composable
private fun AccountFormDialog(form: AccountForm, problem: String?, saving: Boolean, onChange: (AccountForm) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (form.isNew) "New account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.name, { onChange(form.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                if (form.isNew) OutlinedTextField(form.email, { onChange(form.copy(email = it)) }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                else Text(form.email, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(form.machineLimit, { value -> onChange(form.copy(machineLimit = value.filter(Char::isDigit).take(2))) }, Modifier.fillMaxWidth(),
                    label = { Text("Machine limit") }, supportingText = { Text("Computers this account may use, 1 to ${CosmosAdmin.MAX_MACHINE_LIMIT}.") },
                    singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                if (form.isNew) Text("Cosmos creates a temporary password, shown once after you create the account.", style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = { Button(onClick = onSave, enabled = !saving) { Text(when { saving -> "Saving…"; form.isNew -> "Create account"; else -> "Save" }) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(account: CosmosAdminAccount, action: AccountAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, button) = when (action) {
        AccountAction.Disable -> Triple("Disable ${account.name}?", "${account.name} is signed out on every computer and browser at once, and can't sign in until you enable the account again.", "Disable")
        else -> Triple("Reset ${account.name}'s password?", "Cosmos creates a new temporary password and signs ${account.name} out everywhere. ${account.name} chooses a new password at the next sign-in.", "Reset password")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
