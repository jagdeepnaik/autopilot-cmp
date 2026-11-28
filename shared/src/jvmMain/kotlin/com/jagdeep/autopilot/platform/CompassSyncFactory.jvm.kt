package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.data.compass.KtorCompassApi
import com.jagdeep.home.data.compass.SqliteCompassQaStore
import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.compass.CompassCredentials
import com.jagdeep.home.domain.compass.CompassSyncEngine
import com.jagdeep.persistence.preferences.SystemSecurePreferencesStore
import com.jagdeep.persistence.preferences.systemSecretBackend
import com.jagdeep.shared.compass.CompassSync
import com.jagdeep.shared.compass.CompassSyncState
import com.jagdeep.shared.compass.NoCompassSync
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Overrides the built-in Compass address for one run, for example a local test server. */
internal const val COMPASS_URL_PROPERTY = "autopilot.compassUrl"

/** The Compass address: the build's `compassUrl` (version catalog, or -PcompassUrl), unless overridden. */
internal fun compassBaseUrl(): String? =
    System.getProperty(COMPASS_URL_PROPERTY)?.takeIf { it.isNotBlank() }
        ?: object {}.javaClass.getResourceAsStream("/compass/compass.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("url")
        }?.takeIf { it.isNotBlank() }

internal actual fun createCompassSync(answerBanks: CourseAnswerBankStore, scope: CoroutineScope, runLogs: RunLogServices, log: (String) -> Unit): CompassSync {
    val files = answerBanks as? SqliteCourseAnswerBanks ?: return NoCompassSync
    val url = compassBaseUrl()?.takeIf(KtorCompassApi::isAllowedBaseUrl) ?: return NoCompassSync
    // The refresh token goes only to the Keychain, DPAPI or the Secret Service: never to a plain file.
    val backend = systemSecretBackend("AutoPilot Compass", "refresh-token", File(desktopDataPaths.root, "secure"))
        ?: return UnavailableCompassSync
    val engine = CompassSyncEngine(
        api = KtorCompassApi(url, appBuildIdentity, CIO.create()),
        local = SqliteCompassQaStore(files),
        credentials = SystemSecurePreferencesStore(backend, CompassCredentials.serializer()),
        scope = scope,
        now = System::currentTimeMillis,
        deviceName = "AutoPilot on ${desktopName()}",
        log = log,
        runLogs = runLogs.outbox,
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
private object UnavailableCompassSync : CompassSync by NoCompassSync {
    override val state: StateFlow<CompassSyncState> = MutableStateFlow(CompassSyncState(available = false))
}
