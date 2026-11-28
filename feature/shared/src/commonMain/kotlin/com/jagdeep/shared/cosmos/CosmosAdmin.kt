package com.jagdeep.shared.cosmos

/**
 * The admin's account and machine administration on Cosmos (private/docs/shared/03_ACCOUNTS_AND_MACHINES_API.md).
 * Cosmos checks the admin role on every request; the app only hides the pages from other accounts.
 * Temporary passwords are returned once and must never be logged or stored.
 */
interface CosmosAdmin {
    suspend fun accounts(query: CosmosAccountQuery): CosmosAdminResult<CosmosPage<CosmosAdminAccount>>
    suspend fun createAccount(name: String, email: String, machineLimit: Int): CosmosAdminResult<CosmosCreatedAccount>
    /** Null fields stay as they are. */
    suspend fun updateAccount(id: String, name: String? = null, machineLimit: Int? = null, status: CosmosAccountStatus? = null): CosmosAdminResult<CosmosAdminAccount>
    suspend fun resetPassword(id: String): CosmosAdminResult<CosmosTemporaryPassword>
    suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int): CosmosAdminResult<CosmosPage<CosmosAdminMachine>>
    /** [CosmosProblem.Conflict] means the account already has its limit of approved machines. */
    suspend fun approveMachine(id: String): CosmosAdminResult<CosmosAdminMachine>
    suspend fun revokeMachine(id: String): CosmosAdminResult<CosmosAdminMachine>

    companion object {
        const val DEFAULT_MACHINE_LIMIT = 1
        const val MAX_MACHINE_LIMIT = 20
    }
}

sealed interface CosmosAdminResult<out T> {
    data class Done<T>(val value: T) : CosmosAdminResult<T>
    data class Failed(val problem: CosmosProblem) : CosmosAdminResult<Nothing>
}

data class CosmosPage<T>(val items: List<T>, val page: Int, val pageSize: Int, val total: Int)

data class CosmosAccountQuery(val status: CosmosAccountStatus? = null, val search: String = "", val page: Int = 1, val pageSize: Int = 50)

enum class CosmosAccountStatus(val wire: String) { Active("active"), Disabled("disabled");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

enum class CosmosMachineStatus(val wire: String) { Pending("pending"), Approved("approved"), Revoked("revoked");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

data class CosmosAdminAccount(
    val id: String,
    val name: String,
    val email: String,
    val role: String?,
    val status: CosmosAccountStatus?,
    val machineLimit: Int,
    val approvedMachines: Int,
    val pendingMachines: Int,
    val mustChangePassword: Boolean,
    val createdAt: String?,
    val lastSignInAt: String?,
) {
    val isAdmin: Boolean get() = role == CosmosAccount.ADMIN_ROLE
}

/** A machine as the admin sees it. The machine's hashed identifier is deliberately left out. */
data class CosmosAdminMachine(
    val id: String,
    val label: String,
    val platform: String?,
    val status: CosmosMachineStatus?,
    val firstSeenAt: String?,
    val lastSeenAt: String?,
    val approvedAt: String?,
    val accountId: String?,
    val accountName: String?,
    val accountEmail: String?,
)

/** Shown once to the admin; [toString] never prints the password. */
class CosmosTemporaryPassword(val value: String) { override fun toString() = "CosmosTemporaryPassword(…)" }

data class CosmosCreatedAccount(val account: CosmosAdminAccount, val temporaryPassword: CosmosTemporaryPassword)

object NoCosmosAdmin : CosmosAdmin {
    private val unavailable = CosmosAdminResult.Failed(CosmosProblem.Unavailable)
    override suspend fun accounts(query: CosmosAccountQuery) = unavailable
    override suspend fun createAccount(name: String, email: String, machineLimit: Int) = unavailable
    override suspend fun updateAccount(id: String, name: String?, machineLimit: Int?, status: CosmosAccountStatus?) = unavailable
    override suspend fun resetPassword(id: String) = unavailable
    override suspend fun machines(status: CosmosMachineStatus?, page: Int, pageSize: Int) = unavailable
    override suspend fun approveMachine(id: String) = unavailable
    override suspend fun revokeMachine(id: String) = unavailable
}
