package com.jagdeep.settings.domain

import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.cosmos.CosmosAccount
import com.jagdeep.shared.cosmos.CosmosAccountQuery
import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosAdminResult
import com.jagdeep.shared.cosmos.CosmosBrowserLinkResult
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CosmosAccountsSettingsTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private fun account(id: String, name: String = "Asha", status: CosmosAccountStatus = CosmosAccountStatus.Active, role: String = "seafarer", limit: Int = 1) =
        CosmosAdminAccount(id, name, "$id@example.com", role, status, limit, 0, 0, false, "2026-11-28T10:00:00.000Z", null)

    private fun machine(id: String, status: CosmosMachineStatus) =
        CosmosAdminMachine(id, "AutoPilot on Windows", "windows", status, null, null, null, "a_1", "Asha", "asha@example.com")

    private class FakeAdmin : CosmosAdmin {
        var accounts = mutableListOf<CosmosAdminAccount>()
        val queries = mutableListOf<CosmosAccountQuery>()
        var createResult: CosmosAdminResult<CosmosCreatedAccount>? = null
        val creates = mutableListOf<Triple<String, String, Int>>()
        val updates = mutableListOf<Triple<String, Int?, CosmosAccountStatus?>>()
        var machines = mutableListOf<CosmosAdminMachine>()
        var approveProblem: CosmosProblem? = null
        val approvals = mutableListOf<String>()
        override suspend fun accounts(query: CosmosAccountQuery): CosmosAdminResult<CosmosPage<CosmosAdminAccount>> {
            queries += query
            return CosmosAdminResult.Done(CosmosPage(accounts.toList(), query.page, query.pageSize, accounts.size))
        }
        override suspend fun createAccount(name: String, email: String, machineLimit: Int): CosmosAdminResult<CosmosCreatedAccount> {
            creates += Triple(name, email, machineLimit)
            return createResult ?: CosmosAdminResult.Done(CosmosCreatedAccount(
                CosmosAdminAccount("a_new", name, email, "seafarer", CosmosAccountStatus.Active, machineLimit, 0, 0, true, null, null), CosmosTemporaryPassword("temp-secret-123")))
        }
        override suspend fun updateAccount(id: String, name: String?, machineLimit: Int?, status: CosmosAccountStatus?): CosmosAdminResult<CosmosAdminAccount> {
            updates += Triple(id, machineLimit, status)
            val old = accounts.first { it.id == id }
            return CosmosAdminResult.Done(old.copy(name = name ?: old.name, machineLimit = machineLimit ?: old.machineLimit, status = status ?: old.status))
        }
        override suspend fun resetPassword(id: String) = CosmosAdminResult.Done(CosmosTemporaryPassword("reset-secret-456"))
        override suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int) =
            CosmosAdminResult.Done(CosmosPage(machines.filter { status == null || it.status == status }, page, pageSize, machines.size))
        override suspend fun approveMachine(id: String): CosmosAdminResult<CosmosAdminMachine> {
            approvals += id
            approveProblem?.let { return CosmosAdminResult.Failed(it) }
            return CosmosAdminResult.Done(machines.first { it.id == id }.copy(status = CosmosMachineStatus.Approved, accountName = null))
        }
        override suspend fun revokeMachine(id: String) = CosmosAdminResult.Done(machines.first { it.id == id }.copy(status = CosmosMachineStatus.Revoked))
    }

    private class FakeSync : CosmosSync {
        override val state = MutableStateFlow(CosmosSyncState(available = true))
        var signInResult = CosmosSignInResult.SignedIn
        var changeResult = CosmosPasswordChangeResult.Changed
        val changes = mutableListOf<Pair<String, String>>()
        var link: CosmosBrowserLinkResult = CosmosBrowserLinkResult.Link("https://cosmos.test/link#secret-link-token")
        var links = 0
        override suspend fun signIn(email: String, password: String) = signInResult
        override suspend fun signOut() = Unit
        override fun syncNow() = Unit
        override fun answersChanged() = Unit
        override suspend fun changePassword(currentPassword: String, newPassword: String): CosmosPasswordChangeResult {
            changes += currentPassword to newPassword
            if (changeResult == CosmosPasswordChangeResult.Changed) state.value = state.value.copy(passwordChangeRequired = false)
            return changeResult
        }
        override suspend fun browserLink(): CosmosBrowserLinkResult { links++; return link }
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(); advanceUntilIdle() } finally { Dispatchers.resetMain() }
    }

    private fun settings(sync: CosmosSync, openBrowser: suspend (String) -> Boolean = { true }) =
        SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync, openBrowser = openBrowser)

    @Test fun given_a_computer_waiting_for_approval_when_signing_in_then_settings_says_so_and_offers_try_again() = test {
        val sync = FakeSync().apply { signInResult = CosmosSignInResult.MachinePending }
        val vm = settings(sync)
        vm.signInToCosmos("person@example.com", "test-password")
        advanceUntilIdle()
        assertTrue(vm.state.value.cosmosMachinePending)
        assertTrue(vm.state.value.cosmosSignInProblem!!.startsWith("This computer is waiting for the admin's approval."))
        sync.signInResult = CosmosSignInResult.SignedIn
        vm.signInToCosmos("person@example.com", "test-password")
        advanceUntilIdle()
        assertFalse(vm.state.value.cosmosMachinePending)
        assertNull(vm.state.value.cosmosSignInProblem)
    }

    @Test fun given_a_revoked_machine_or_disabled_account_when_signing_in_then_each_is_explained() = test {
        val sync = FakeSync()
        val vm = settings(sync)
        sync.signInResult = CosmosSignInResult.MachineRevoked
        vm.signInToCosmos("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals("The admin removed this computer from your account. Ask the admin to approve it again.", vm.state.value.cosmosSignInProblem)
        sync.signInResult = CosmosSignInResult.AccountDisabled
        vm.signInToCosmos("person@example.com", "test-password")
        advanceUntilIdle()
        assertEquals("This Cosmos account is disabled. Ask the admin to enable it.", vm.state.value.cosmosSignInProblem)
    }

    @Test fun given_a_temporary_password_when_changing_it_then_short_ones_are_refused_and_neither_is_kept() = test {
        val sync = FakeSync().apply { state.value = state.value.copy(account = CosmosAccount("Tester"), passwordChangeRequired = true) }
        val vm = settings(sync)
        advanceUntilIdle()
        vm.changeCosmosPassword("temporary-pass", "short")
        advanceUntilIdle()
        assertTrue(sync.changes.isEmpty())
        assertEquals("Choose a new password with at least 12 characters.", vm.state.value.cosmosPasswordProblem)
        vm.changeCosmosPassword("temporary-pass", "a-much-longer-password")
        advanceUntilIdle()
        assertEquals(listOf("temporary-pass" to "a-much-longer-password"), sync.changes)
        assertNull(vm.state.value.cosmosPasswordProblem)
        assertFalse(vm.state.value.cosmos.passwordChangeRequired)
        assertFalse("a-much-longer-password" in vm.state.value.toString() || "temporary-pass" in vm.state.value.toString())
        assertEquals("Password changed. Syncing answers now.", vm.messages.first())
    }

    @Test fun given_a_wrong_current_password_when_changing_then_it_is_explained() = test {
        val sync = FakeSync().apply { state.value = state.value.copy(account = CosmosAccount("Tester"), passwordChangeRequired = true); changeResult = CosmosPasswordChangeResult.WrongCurrentPassword }
        val vm = settings(sync)
        vm.changeCosmosPassword("not-it", "a-much-longer-password")
        advanceUntilIdle()
        assertEquals("That current password isn't right. Enter the temporary password you signed in with.", vm.state.value.cosmosPasswordProblem)
        CosmosPasswordChangeResult.entries.filter { it != CosmosPasswordChangeResult.Changed }.forEach { assertTrue(cosmosPasswordProblem(it)!!.endsWith("."), it.name) }
    }

    @Test fun given_signed_in_when_open_cosmos_is_chosen_then_the_link_opens_in_the_browser_and_isnt_kept() = test {
        val sync = FakeSync().apply { state.value = state.value.copy(account = CosmosAccount("Tester")) }
        val opened = mutableListOf<String>()
        val vm = settings(sync) { opened += it; true }
        vm.openCosmos()
        advanceUntilIdle()
        assertEquals(listOf("https://cosmos.test/link#secret-link-token"), opened)
        assertFalse(vm.state.value.cosmosOpeningBrowser)
        assertFalse("secret-link-token" in vm.state.value.toString())
    }

    @Test fun given_open_cosmos_fails_then_the_reason_is_shown_in_plain_words() = test {
        val sync = FakeSync().apply { link = CosmosBrowserLinkResult.Failed(CosmosProblem.Offline) }
        val vm = settings(sync)
        vm.openCosmos()
        assertEquals("Couldn't open Cosmos: Cosmos can't be reached. Check your internet connection and try again.", vm.messages.first())
        sync.link = CosmosBrowserLinkResult.Link("https://cosmos.test/link#t")
        val noBrowser = settings(sync) { false }
        noBrowser.openCosmos()
        assertEquals("Couldn't open your web browser. Check that a default browser is set, then try again.", noBrowser.messages.first())
    }

    @Test fun given_the_admin_creates_an_account_then_the_temporary_password_is_shown_once_and_then_forgotten() = test {
        val admin = FakeAdmin()
        val vm = CosmosAccountsViewModel(admin)
        advanceUntilIdle()
        vm.startCreate()
        vm.updateForm(vm.state.value.form!!.copy(name = " Asha ", email = "asha@example.com", machineLimit = "2"))
        vm.saveForm()
        advanceUntilIdle()
        assertEquals(Triple("Asha", "asha@example.com", 2), admin.creates.single())
        val shown = vm.state.value.shownPassword!!
        assertEquals("temp-secret-123", shown.password)
        assertTrue(shown.isNewAccount)
        assertFalse(shown.toString().contains("temp-secret-123"))
        assertNull(vm.state.value.form)
        assertEquals("Asha", vm.state.value.accounts.first().name)
        vm.dismissPassword()
        assertNull(vm.state.value.shownPassword)
        assertFalse(vm.state.value.toString().contains("temp-secret-123"))
    }

    @Test fun given_invalid_details_or_a_taken_email_when_creating_then_the_form_explains_and_stays_open() = test {
        val admin = FakeAdmin()
        val vm = CosmosAccountsViewModel(admin)
        vm.startCreate()
        vm.updateForm(AccountForm(name = "Asha", email = "not-an-email", machineLimit = "1"))
        vm.saveForm()
        assertEquals("Enter an email address, for example name@example.com.", vm.state.value.formProblem)
        vm.updateForm(AccountForm(name = "Asha", email = "asha@example.com", machineLimit = "21"))
        vm.saveForm()
        assertEquals("Choose a machine limit from 1 to 20.", vm.state.value.formProblem)
        assertTrue(admin.creates.isEmpty())
        admin.createResult = CosmosAdminResult.Failed(CosmosProblem.Conflict)
        vm.updateForm(AccountForm(name = "Asha", email = "asha@example.com", machineLimit = "1"))
        vm.saveForm()
        advanceUntilIdle()
        assertEquals("An account with this email already exists.", vm.state.value.formProblem)
        assertTrue(vm.state.value.form != null)
        assertNull(vm.state.value.shownPassword)
    }

    @Test fun given_accounts_when_searching_editing_disabling_and_resetting_then_each_reaches_cosmos() = test {
        val admin = FakeAdmin().apply { accounts = mutableListOf(account("a_1", limit = 1)) }
        val vm = CosmosAccountsViewModel(admin, searchDelayMillis = 300)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.accounts.size)
        vm.updateSearch("as")
        vm.updateSearch("asha")
        advanceUntilIdle()
        // Typing waits for a pause: one search for the final text.
        assertEquals(listOf("", "asha"), admin.queries.map { it.search })
        vm.updateStatus(CosmosAccountStatus.Disabled)
        advanceUntilIdle()
        assertEquals(CosmosAccountStatus.Disabled, admin.queries.last().status)

        vm.startEdit(vm.state.value.accounts.single())
        vm.updateForm(vm.state.value.form!!.copy(machineLimit = "3"))
        vm.saveForm()
        advanceUntilIdle()
        assertEquals(Triple("a_1", 3, null), admin.updates.last())
        assertEquals(3, vm.state.value.accounts.single().machineLimit)

        vm.setEnabled(vm.state.value.accounts.single(), enabled = false)
        advanceUntilIdle()
        assertEquals(CosmosAccountStatus.Disabled, admin.updates.last().third)
        assertEquals(CosmosAccountStatus.Disabled, vm.state.value.accounts.single().status)

        vm.resetPassword(vm.state.value.accounts.single())
        advanceUntilIdle()
        assertEquals("reset-secret-456", vm.state.value.shownPassword?.password)
        assertFalse(vm.state.value.shownPassword!!.isNewAccount)
        assertTrue(vm.state.value.accounts.single().mustChangePassword)
    }

    @Test fun given_machines_then_waiting_ones_come_first_and_an_approval_over_the_limit_is_explained() = test {
        val admin = FakeAdmin().apply { machines = mutableListOf(machine("m_a", CosmosMachineStatus.Approved), machine("m_p", CosmosMachineStatus.Pending), machine("m_r", CosmosMachineStatus.Revoked)) }
        val vm = CosmosMachinesViewModel(admin)
        advanceUntilIdle()
        assertEquals(listOf("m_p", "m_a", "m_r"), vm.state.value.machines.map { it.id })
        admin.approveProblem = CosmosProblem.Conflict
        vm.approve(vm.state.value.machines.first())
        advanceUntilIdle()
        assertEquals("Asha's account already has its limit of approved computers. Raise its machine limit on the Accounts page, or revoke another of its computers first.",
            vm.state.value.actionProblem)
        assertEquals(CosmosMachineStatus.Pending, vm.state.value.machines.first().status)
        admin.approveProblem = null
        vm.approve(vm.state.value.machines.first())
        advanceUntilIdle()
        assertNull(vm.state.value.actionProblem)
        val approved = vm.state.value.machines.first { it.id == "m_p" }
        assertEquals(CosmosMachineStatus.Approved, approved.status)
        // The account shown before is kept when the reply leaves it out.
        assertEquals("Asha", approved.accountName)
        vm.revoke(approved)
        advanceUntilIdle()
        assertEquals(CosmosMachineStatus.Revoked, vm.state.value.machines.first { it.id == "m_p" }.status)
    }

    @Test fun given_the_admin_pages_without_access_then_the_problem_is_explained() = test {
        val failing = object : CosmosAdmin by FakeAdmin() {
            override suspend fun accounts(query: CosmosAccountQuery) = CosmosAdminResult.Failed(CosmosProblem.NotAllowed)
            override suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int) = CosmosAdminResult.Failed(CosmosProblem.SignInNeeded)
        }
        val accounts = CosmosAccountsViewModel(failing)
        val machines = CosmosMachinesViewModel(failing)
        advanceUntilIdle()
        assertEquals("Couldn't load the accounts: this account isn't allowed to do that.", accounts.state.value.problem)
        assertEquals("Couldn't load the machines: your Cosmos session ended. Sign in again in Settings.", machines.state.value.problem)
    }
}
