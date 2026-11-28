package com.jagdeep.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.shared.cosmos.CosmosAccountQuery
import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.shared.cosmos.CosmosAdminResult
import com.jagdeep.shared.cosmos.CosmosProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The create or edit form; [accountId] is null for a new account. Values are as typed. */
data class AccountForm(val accountId: String? = null, val name: String = "", val email: String = "", val machineLimit: String = CosmosAdmin.DEFAULT_MACHINE_LIMIT.toString()) {
    val isNew: Boolean get() = accountId == null
}

/** A temporary password shown to the admin once. [toString] never prints it. */
class ShownPassword(val accountName: String, val password: String, val isNewAccount: Boolean) {
    override fun toString() = "ShownPassword(account=$accountName)"
}

data class CosmosAccountsUiState(
    val search: String = "",
    /** Null shows every account. */
    val status: CosmosAccountStatus? = null,
    val accounts: List<CosmosAdminAccount> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    /** Why the list couldn't load; null when there's nothing to explain. */
    val problem: String? = null,
    val form: AccountForm? = null,
    val formProblem: String? = null,
    val saving: Boolean = false,
    /** Account ids with a change on its way. */
    val busy: Set<String> = emptySet(),
    /** Held only until the admin closes it; never saved or logged. */
    val shownPassword: ShownPassword? = null,
) {
    val canLoadMore: Boolean get() = accounts.size < total && !loading
}

/** The admin's Accounts page: search, create, edit name and machine limit, disable or enable, reset password. */
class CosmosAccountsViewModel(
    private val admin: CosmosAdmin,
    private val pageSize: Int = 50,
    private val searchDelayMillis: Long = 300,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CosmosAccountsUiState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()
    private var loadJob: Job? = null

    init { reload() }

    fun updateSearch(search: String) {
        mutableState.update { it.copy(search = search) }
        reload(delayMillis = searchDelayMillis)
    }

    fun updateStatus(status: CosmosAccountStatus?) {
        if (status == state.value.status) return
        mutableState.update { it.copy(status = status) }
        reload()
    }

    fun reload(delayMillis: Long = 0) = load(page = 1, delayMillis = delayMillis)

    fun loadMore() {
        if (!state.value.canLoadMore) return
        load(page = state.value.accounts.size / pageSize + 1)
    }

    private fun load(page: Int, delayMillis: Long = 0) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            mutableState.update { it.copy(loading = true, problem = null) }
            val current = state.value
            val result = safely { admin.accounts(CosmosAccountQuery(current.status, current.search.trim(), page, pageSize)) }
            mutableState.update { state -> when (result) {
                is CosmosAdminResult.Done -> state.copy(loading = false, total = result.value.total,
                    accounts = if (page == 1) result.value.items else (state.accounts + result.value.items).distinctBy { it.id })
                is CosmosAdminResult.Failed -> state.copy(loading = false, problem = cosmosProblemText(result.problem, "load the accounts"))
            } }
        }
    }

    fun startCreate() = mutableState.update { it.copy(form = AccountForm(), formProblem = null) }

    fun startEdit(account: CosmosAdminAccount) =
        mutableState.update { it.copy(form = AccountForm(account.id, account.name, account.email, account.machineLimit.toString()), formProblem = null) }

    fun updateForm(form: AccountForm) = mutableState.update { it.copy(form = form, formProblem = null) }

    fun cancelForm() { if (!state.value.saving) mutableState.update { it.copy(form = null, formProblem = null) } }

    fun saveForm() {
        val form = state.value.form ?: return
        if (state.value.saving) return
        val name = form.name.trim()
        val email = form.email.trim()
        val limit = form.machineLimit.trim().toIntOrNull()
        val invalid = when {
            name.isEmpty() -> "Enter the person's name."
            name.length > MAX_NAME -> "Use a name of at most $MAX_NAME characters."
            form.isNew && !EMAIL.matches(email) -> "Enter an email address, for example name@example.com."
            limit == null || limit !in 1..CosmosAdmin.MAX_MACHINE_LIMIT -> "Choose a machine limit from 1 to ${CosmosAdmin.MAX_MACHINE_LIMIT}."
            else -> null
        }
        if (invalid != null) { mutableState.update { it.copy(formProblem = invalid) }; return }
        mutableState.update { it.copy(saving = true, formProblem = null) }
        viewModelScope.launch {
            if (form.isNew) {
                when (val result = safely { admin.createAccount(name, email, limit!!) }) {
                    is CosmosAdminResult.Done -> {
                        val created = result.value
                        mutableState.update { it.copy(saving = false, form = null, accounts = listOf(created.account) + it.accounts.filter { a -> a.id != created.account.id },
                            total = it.total + 1, shownPassword = ShownPassword(created.account.name, created.temporaryPassword.value, isNewAccount = true)) }
                    }
                    is CosmosAdminResult.Failed -> mutableState.update { it.copy(saving = false, formProblem =
                        if (result.problem == CosmosProblem.Conflict) "An account with this email already exists." else cosmosProblemText(result.problem, "create the account")) }
                }
            } else {
                val original = state.value.accounts.firstOrNull { it.id == form.accountId }
                val result = safely { admin.updateAccount(form.accountId!!, name = name.takeIf { it != original?.name }, machineLimit = limit.takeIf { it != original?.machineLimit }) }
                when (result) {
                    is CosmosAdminResult.Done -> { replace(result.value); mutableState.update { it.copy(saving = false, form = null) }; mutableMessages.send("Saved ${result.value.name}.") }
                    is CosmosAdminResult.Failed -> mutableState.update { it.copy(saving = false, formProblem =
                        if (result.problem == CosmosProblem.Conflict) "That machine limit is below the computers already approved. Revoke one on the Machines page first."
                        else cosmosProblemText(result.problem, "save the account")) }
                }
            }
        }
    }

    /** Disabling ends every session and browser link of the account at once (the screen confirms first). */
    fun setEnabled(account: CosmosAdminAccount, enabled: Boolean) = busy(account) {
        val status = if (enabled) CosmosAccountStatus.Active else CosmosAccountStatus.Disabled
        when (val result = safely { admin.updateAccount(account.id, status = status) }) {
            is CosmosAdminResult.Done -> { replace(result.value); mutableMessages.send(if (enabled) "${account.name} can sign in again." else "${account.name} is disabled and signed out everywhere.") }
            is CosmosAdminResult.Failed -> mutableMessages.send(cosmosProblemText(result.problem, if (enabled) "enable ${account.name}" else "disable ${account.name}"))
        }
    }

    /** A new temporary password, shown once; it ends the account's sessions (the screen confirms first). */
    fun resetPassword(account: CosmosAdminAccount) = busy(account) {
        when (val result = safely { admin.resetPassword(account.id) }) {
            is CosmosAdminResult.Done -> {
                replace(account.copy(mustChangePassword = true))
                mutableState.update { it.copy(shownPassword = ShownPassword(account.name, result.value.value, isNewAccount = false)) }
            }
            is CosmosAdminResult.Failed -> mutableMessages.send(cosmosProblemText(result.problem, "reset the password"))
        }
    }

    /** Forgets the temporary password: it is never shown again. */
    fun dismissPassword() = mutableState.update { it.copy(shownPassword = null) }

    override fun onCleared() { mutableState.update { it.copy(shownPassword = null) } }

    private fun busy(account: CosmosAdminAccount, action: suspend () -> Unit) {
        if (account.id in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + account.id) }
        viewModelScope.launch {
            try { action() } finally { mutableState.update { it.copy(busy = it.busy - account.id) } }
        }
    }

    private fun replace(account: CosmosAdminAccount) =
        mutableState.update { state -> state.copy(accounts = state.accounts.map { if (it.id == account.id) account else it }) }

    private companion object {
        const val MAX_NAME = 100
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}

/** Runs an admin call, turning an unexpected failure into a plain problem; cancellation still cancels. */
internal suspend fun <T> safely(call: suspend () -> CosmosAdminResult<T>): CosmosAdminResult<T> =
    try { call() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { CosmosAdminResult.Failed(CosmosProblem.ServerTrouble) }
