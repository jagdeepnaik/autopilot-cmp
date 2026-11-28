package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.cosmos.KtorCosmosApi
import com.jagdeep.shared.update.NoUpdateCheck
import com.jagdeep.shared.update.UpdateCheck
import com.jagdeep.shared.update.UpdateChecker
import com.jagdeep.shared.update.buildNumberOf
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope

internal actual fun createUpdateCheck(scope: CoroutineScope): UpdateCheck {
    val url = cosmosBaseUrl()?.takeIf(KtorCosmosApi::isAllowedBaseUrl) ?: return NoUpdateCheck
    val platform = desktopUpdatePlatform() ?: return NoUpdateCheck
    val api = KtorCosmosApi(url, appBuildIdentity, CIO.create())
    return UpdateChecker(scope, buildNumberOf(appBuildIdentity), System::currentTimeMillis) { api.latestBuild(platform) }
}

/** The contract's platform name for this desktop, or null where Cosmos publishes nothing for it. */
internal fun desktopUpdatePlatform(osName: String = System.getProperty("os.name").orEmpty()): String? = when {
    osName.startsWith("Mac", ignoreCase = true) -> "macos"
    osName.startsWith("Windows", ignoreCase = true) -> "windows"
    osName.contains("Linux", ignoreCase = true) -> "linux"
    else -> null
}
