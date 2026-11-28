package com.jagdeep.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.shared.cosmos.CosmosAdmin
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosAdminResult
import com.jagdeep.shared.cosmos.CosmosMachineStatus
import com.jagdeep.shared.cosmos.CosmosProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CosmosMachinesUiState(
    /** Null shows every machine, waiting ones first. */
    val status: CosmosMachineStatus? = null,
    val machines: List<CosmosAdminMachine> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val problem: String? = null,
    /** Why the last approve or revoke didn't work (for example the account's machine limit). */
    val actionProblem: String? = null,
    val busy: Set<String> = emptySet(),
) {
    val canLoadMore: Boolean get() = machines.size < total && !loading
}

/** The admin's Machines page: computers waiting for approval first, then approve or revoke. */
class CosmosMachinesViewModel(
    private val admin: CosmosAdmin,
    private val pageSize: Int = 50,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CosmosMachinesUiState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()
    private var loadJob: Job? = null

    init { reload() }

    fun updateStatus(status: CosmosMachineStatus?) {
        if (status == state.value.status) return
        mutableState.update { it.copy(status = status) }
        reload()
    }

    fun reload() = load(page = 1)

    fun loadMore() { if (state.value.canLoadMore) load(page = state.value.machines.size / pageSize + 1) }

    private fun load(page: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, problem = null) }
            val result = safely { admin.machines(state.value.status, page, pageSize) }
            mutableState.update { state -> when (result) {
                is CosmosAdminResult.Done -> state.copy(loading = false, total = result.value.total,
                    machines = pendingFirst(if (page == 1) result.value.items else (state.machines + result.value.items).distinctBy { it.id }))
                is CosmosAdminResult.Failed -> state.copy(loading = false, problem = cosmosProblemText(result.problem, "load the machines"))
            } }
        }
    }

    fun approve(machine: CosmosAdminMachine) = act(machine) {
        when (val result = safely { admin.approveMachine(machine.id) }) {
            is CosmosAdminResult.Done -> { replace(result.value); mutableMessages.send("Approved ${machine.label.ifBlank { "the computer" }}.") }
            is CosmosAdminResult.Failed -> mutableState.update { it.copy(actionProblem =
                if (result.problem == CosmosProblem.Conflict) limitReached(machine) else cosmosProblemText(result.problem, "approve the computer")) }
        }
    }

    /** Ends that computer's app sessions and browser links for the account at once (the screen confirms first). */
    fun revoke(machine: CosmosAdminMachine) = act(machine) {
        when (val result = safely { admin.revokeMachine(machine.id) }) {
            is CosmosAdminResult.Done -> { replace(result.value); mutableMessages.send("Revoked ${machine.label.ifBlank { "the computer" }}. It's signed out now.") }
            is CosmosAdminResult.Failed -> mutableState.update { it.copy(actionProblem = cosmosProblemText(result.problem, "revoke the computer")) }
        }
    }

    fun dismissActionProblem() = mutableState.update { it.copy(actionProblem = null) }

    private fun act(machine: CosmosAdminMachine, action: suspend () -> Unit) {
        if (machine.id in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + machine.id, actionProblem = null) }
        viewModelScope.launch {
            try { action() } finally { mutableState.update { it.copy(busy = it.busy - machine.id) } }
        }
    }

    private fun replace(machine: CosmosAdminMachine) = mutableState.update { state ->
        // The reply may leave out the account; keep the one already shown.
        state.copy(machines = pendingFirst(state.machines.map { old ->
            if (old.id != machine.id) old
            else machine.copy(accountId = machine.accountId ?: old.accountId, accountName = machine.accountName ?: old.accountName, accountEmail = machine.accountEmail ?: old.accountEmail)
        }))
    }

    private fun limitReached(machine: CosmosAdminMachine): String {
        val owner = machine.accountName?.let { "$it's account" } ?: "This account"
        return "$owner already has its limit of approved computers. Raise its machine limit on the Accounts page, or revoke another of its computers first."
    }

    private companion object {
        val ORDER = listOf(CosmosMachineStatus.Pending, CosmosMachineStatus.Approved, CosmosMachineStatus.Revoked)

        /** Waiting computers first; the server's order is kept within each status. */
        fun pendingFirst(machines: List<CosmosAdminMachine>) = machines.sortedBy { ORDER.indexOf(it.status).let { index -> if (index < 0) ORDER.size else index } }
    }
}
