package com.jagdeep.settings.ui.util

import com.jagdeep.shared.cosmos.CosmosAccountStatus
import com.jagdeep.shared.cosmos.CosmosAdminAccount
import com.jagdeep.shared.cosmos.CosmosAdminMachine
import com.jagdeep.shared.cosmos.CosmosMachineStatus

/** One account's row: email, status, approved computers against the limit, and what's waiting. */
internal fun accountSummary(account: CosmosAdminAccount): String = listOfNotNull(
    account.email,
    when (account.status) {
        CosmosAccountStatus.Disabled -> "Disabled"
        CosmosAccountStatus.Active -> if (account.isAdmin) "Admin" else "Active"
        null -> null
    },
    "${account.approvedMachines} of ${account.machineLimit} ${if (account.machineLimit == 1) "computer" else "computers"}",
    account.pendingMachines.takeIf { it > 0 }?.let { "$it waiting" },
    "New password needed".takeIf { account.mustChangePassword },
).joinToString(" · ")

internal fun machineStatusText(status: CosmosMachineStatus?): String = when (status) {
    CosmosMachineStatus.Pending -> "Waiting for approval"
    CosmosMachineStatus.Approved -> "Approved"
    CosmosMachineStatus.Revoked -> "Revoked"
    null -> "Unknown status"
}

internal fun platformName(platform: String?): String? = when (platform) {
    "macos" -> "macOS"
    "windows" -> "Windows"
    "linux" -> "Linux"
    "android" -> "Android"
    else -> null
}

/** One machine's row: whose it is, its platform and status, and when it was last seen. */
internal fun machineSummary(machine: CosmosAdminMachine, nowMillis: Long): String = listOfNotNull(
    listOfNotNull(machine.accountName, machine.accountEmail?.let { "($it)" }).joinToString(" ").takeIf { it.isNotEmpty() },
    platformName(machine.platform),
    machineStatusText(machine.status),
    (machine.lastSeenAt ?: machine.firstSeenAt)?.let(::epochMillis)?.let { "last seen ${timeAgo(nowMillis - it)}" },
).joinToString(" · ")

/** Milliseconds for an ISO-8601 instant from Cosmos, or null when it can't be read. */
internal fun epochMillis(iso: String): Long? = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
