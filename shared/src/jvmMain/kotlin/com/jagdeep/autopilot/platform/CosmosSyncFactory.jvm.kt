package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.data.cosmos.KtorCosmosApi
import com.jagdeep.home.data.cosmos.SqliteCosmosQaStore
import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.cosmos.CosmosCredentials
import com.jagdeep.home.domain.cosmos.CosmosSyncEngine
import com.jagdeep.persistence.preferences.SystemSecurePreferencesStore
import com.jagdeep.persistence.preferences.systemSecretBackend
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncState
import com.jagdeep.shared.cosmos.NoCosmosSync
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Overrides the built-in Cosmos address for one run, for example a local test server. */
internal const val COSMOS_URL_PROPERTY = "autopilot.cosmosUrl"

/** The Cosmos address: the build's `cosmosUrl` (version catalog, or -PcosmosUrl), unless overridden. */
internal fun cosmosBaseUrl(): String? =
    System.getProperty(COSMOS_URL_PROPERTY)?.takeIf { it.isNotBlank() }
        ?: object {}.javaClass.getResourceAsStream("/cosmos/cosmos.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("url")
        }?.takeIf { it.isNotBlank() }

internal actual fun createCosmosSync(answerBanks: CourseAnswerBankStore, scope: CoroutineScope, runLogs: RunLogServices, log: (String) -> Unit): CosmosSync {
    val files = answerBanks as? SqliteCourseAnswerBanks ?: return NoCosmosSync
    val url = cosmosBaseUrl()?.takeIf(KtorCosmosApi::isAllowedBaseUrl) ?: return NoCosmosSync
    // The refresh token goes only to the Keychain, DPAPI or the Secret Service: never to a plain file.
    val backend = systemSecretBackend("AutoPilot Cosmos", "refresh-token", File(desktopDataPaths.root, "secure"))
        ?: return UnavailableCosmosSync
    val engine = CosmosSyncEngine(
        api = KtorCosmosApi(url, appBuildIdentity, CIO.create()),
        local = SqliteCosmosQaStore(files),
        credentials = SystemSecurePreferencesStore(backend, CosmosCredentials.serializer()),
        scope = scope,
        now = System::currentTimeMillis,
        deviceName = "AutoPilot on ${desktopName()}",
        log = log,
        runLogs = runLogs.outbox,
        machine = createMachineIdentifierSource(),
    )
    scope.launch { files.reviewedSaves.collect { engine.answersChanged() } }
    // Each finished run is uploaded shortly after it ends (when signed in).
    scope.launch { runLogs.finished.collect { engine.uploadRunLogs() } }
    engine.start()
    return engine
}

/** The operating system, not the computer's name: the device label is shown to the account holder. */
private fun desktopName(): String {
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac", ignoreCase = true) -> "macOS"
        os.startsWith("Windows", ignoreCase = true) -> "Windows"
        os.contains("Linux", ignoreCase = true) -> "Linux"
        else -> "desktop"
    }
}

/** Desktop without platform secure storage: sync is shown as unavailable rather than storing tokens unsafely. */
private object UnavailableCosmosSync : CosmosSync by NoCosmosSync {
    override val state: StateFlow<CosmosSyncState> = MutableStateFlow(CosmosSyncState(available = false))
}
