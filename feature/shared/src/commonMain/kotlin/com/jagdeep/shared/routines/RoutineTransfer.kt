package com.jagdeep.shared.routines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Non-sensitive file-transfer boundary shared by routine ownership and app settings. */
data class TransferRoutine(val id: String, val name: String)
interface RoutineTransfer {
    val isAvailable: Boolean
    val routines: Flow<List<TransferRoutine>>
    suspend fun importRoutines(): String?
    suspend fun exportRoutines(ids: Set<String>): String?
}
object NoRoutineTransfer : RoutineTransfer {
    override val isAvailable = false
    override val routines = flowOf(emptyList<TransferRoutine>())
    override suspend fun importRoutines(): String? = null
    override suspend fun exportRoutines(ids: Set<String>): String? = null
}
