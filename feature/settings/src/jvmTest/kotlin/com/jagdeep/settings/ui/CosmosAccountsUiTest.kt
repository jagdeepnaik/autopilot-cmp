package com.jagdeep.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.domain.CosmosAccountsViewModel
import com.jagdeep.settings.domain.CosmosMachinesViewModel
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.CosmosAccountsScreen
import com.jagdeep.settings.ui.screens.CosmosMachinesScreen
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.cosmos.CosmosAccount
import com.jagdeep.shared.cosmos.CosmosAccountQuery
import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosAdminResult
import com.jagdeep.shared.cosmos.CosmosCreatedAccount
import com.jagdeep.shared.cosmos.CosmosMachineStatus
import com.jagdeep.shared.cosmos.CosmosPage
import com.jagdeep.shared.cosmos.CosmosPasswordChangeResult
import com.jagdeep.shared.cosmos.CosmosProblem
import com.jagdeep.shared.cosmos.CosmosSignInResult
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncState
import com.jagdeep.shared.cosmos.CosmosTemporaryPassword
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class CosmosAccountsUiTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private class FakeAdmin : CosmosAdmin {
        val accounts = mutableListOf<CosmosAdminAccount>()
        val machines = mutableListOf(CosmosAdminMachine("m_1", "AutoPilot on Windows", "windows", CosmosMachineStatus.Pending, null, null, null, "a_1", "Asha", "asha@example.com"))
        override suspend fun accounts(query: CosmosAccountQuery) = CosmosAdminResult.Done(CosmosPage(accounts.toList(), 1, 50, accounts.size))
        override suspend fun createAccount(name: String, email: String, machineLimit: Int): CosmosAdminResult<CosmosCreatedAccount> = CosmosAdminResult.Done(CosmosCreatedAccount(
            CosmosAdminAccount("a_1", name, email, "seafarer", CosmosAccountStatus.Active, machineLimit, 0, 0, true, null, null), CosmosTemporaryPassword("temp-secret-123")))
        override suspend fun updateAccount(id: String, name: String?, machineLimit: Int?, status: CosmosAccountStatus?) = CosmosAdminResult.Failed(CosmosProblem.ServerTrouble)
        override suspend fun resetPassword(id: String) = CosmosAdminResult.Failed(CosmosProblem.ServerTrouble)
        override suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int) = CosmosAdminResult.Done(CosmosPage(machines.toList(), 1, 50, machines.size))
        override suspend fun approveMachine(id: String) = CosmosAdminResult.Failed(CosmosProblem.Conflict)
        override suspend fun revokeMachine(id: String) = CosmosAdminResult.Failed(CosmosProblem.ServerTrouble)
    }

    private class FakeSync(initial: CosmosSyncState) : CosmosSync {
        override val state = MutableStateFlow(initial)
        val changes = mutableListOf<Pair<String, String>>()
        override suspend fun signIn(email: String, password: String) = CosmosSignInResult.MachinePending
        override suspend fun signOut() = Unit
        override fun syncNow() = Unit
        override fun answersChanged() = Unit
        override suspend fun changePassword(currentPassword: String, newPassword: String): CosmosPasswordChangeResult {
            changes += currentPassword to newPassword
            state.value = state.value.copy(passwordChangeRequired = false)
            return CosmosPasswordChangeResult.Changed
        }
    }

    @Test fun given_the_admin_signed_in_then_settings_offers_open_cosmos_accounts_and_machines() = runDesktopComposeUiTest(1000, 2400) {
        var opened = ""
        val sync = FakeSync(CosmosSyncState(available = true, account = CosmosAccount("Admin", "admin")))
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0", onOpenAccounts = { opened = "accounts" }, onOpenMachines = { opened = "machines" }) } }
        onNodeWithText("Open Cosmos").performScrollTo().assertExists()
        onNodeWithText("Accounts").performScrollTo().performClick()
        assertEquals("accounts", opened)
        onNodeWithText("Machines").performScrollTo().performClick()
        assertEquals("machines", opened)
    }

    @Test fun given_an_ordinary_account_then_the_admin_pages_are_not_offered() = runDesktopComposeUiTest(1000, 2400) {
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = FakeSync(CosmosSyncState(available = true, account = CosmosAccount("Tester", "seafarer"))))
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Open Cosmos").performScrollTo().assertExists()
        onNodeWithText("Accounts").assertDoesNotExist()
        onNodeWithText("Machines").assertDoesNotExist()
    }

    @Test fun given_a_waiting_computer_when_signing_in_then_try_again_is_offered_with_the_password_still_typed() = runDesktopComposeUiTest(1000, 2400) {
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = FakeSync(CosmosSyncState(available = true)))
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Email").performScrollTo().performTextInput("person@example.com")
        onNodeWithText("Password").performTextInput("test-password")
        onNodeWithText("Sign in").performClick()
        waitForIdle()
        onNodeWithText("This computer is waiting for the admin's approval. Ask the admin to approve it, then choose Try again.").assertExists()
        onNodeWithText("Try again").assertIsEnabled()
    }

    @Test fun given_a_temporary_password_then_the_change_form_needs_twelve_characters() = runDesktopComposeUiTest(1000, 2400) {
        val sync = FakeSync(CosmosSyncState(available = true, account = CosmosAccount("Tester"), passwordChangeRequired = true))
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Choose a new Cosmos password").performScrollTo().assertExists()
        onNodeWithText("Current password").performTextInput("temporary-pass")
        onNodeWithText("New password").performTextInput("too-short")
        onNodeWithText("Change password").assertIsNotEnabled()
        onNodeWithText("New password").performTextReplacement("a-much-longer-password")
        onNodeWithText("Change password").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf("temporary-pass" to "a-much-longer-password"), sync.changes)
        onNodeWithText("Signed in to Cosmos as Tester").performScrollTo().assertExists()
    }

    @Test fun given_a_new_account_then_its_temporary_password_is_shown_once_with_copy() = runDesktopComposeUiTest(1000, 1200) {
        val vm = CosmosAccountsViewModel(FakeAdmin())
        setContent { AppTheme(darkTheme = false) { CosmosAccountsScreen(vm, onBack = {}) } }
        onNodeWithText("No accounts yet").assertExists()
        onNodeWithText("New account").performClick()
        onNodeWithText("Name").performTextInput("Asha")
        onNodeWithText("Email").performTextInput("asha@example.com")
        onNodeWithText("Create account").performClick()
        waitForIdle()
        onNodeWithText("temp-secret-123").assertExists()
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Done").performClick()
        waitForIdle()
        onNodeWithText("temp-secret-123").assertDoesNotExist()
        onNodeWithText("Asha").assertExists()
        onNodeWithContentDescription("Actions for Asha").assertExists()
    }

    @Test fun given_a_waiting_machine_over_the_limit_when_approving_then_the_limit_message_shows() = runDesktopComposeUiTest(1000, 1200) {
        val vm = CosmosMachinesViewModel(FakeAdmin())
        setContent { AppTheme(darkTheme = false) { CosmosMachinesScreen(vm, onBack = {}, now = { 0L }) } }
        onNodeWithText("AutoPilot on Windows").assertExists()
        onNodeWithText("Approve").performClick()
        waitForIdle()
        onNodeWithText("Asha's account already has its limit of approved computers. Raise its machine limit on the Accounts page, or revoke another of its computers first.").assertExists()
    }
}
