package com.jagdeep.autopilot.platform

import com.jagdeep.shared.update.LatestBuild
import com.jagdeep.shared.update.NoUpdateCheck
import com.jagdeep.shared.update.UpdateCheck
import com.jagdeep.shared.update.UpdateChecker
import com.jagdeep.shared.update.buildNumberOf
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal actual fun createUpdateCheck(scope: CoroutineScope): UpdateCheck {
    val base = AndroidBuildInfo.COMPASS_URL.trimEnd('/').takeIf { it.startsWith("https://") } ?: return NoUpdateCheck
    return UpdateChecker(scope, buildNumberOf(AndroidBuildInfo.BUILD_IDENTITY), System::currentTimeMillis) { latestAndroidBuild(base) }
}

/** `GET <base>/api/app/android` with the app-version header; null on 404 or any failure. */
private suspend fun latestAndroidBuild(base: String): LatestBuild? = withContext(Dispatchers.IO) {
    val connection = URL("$base/api/app/android").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-AutoPilot-App-Version", AndroidBuildInfo.BUILD_IDENTITY)
        if (connection.responseCode != 200) return@withContext null
        val body = connection.inputStream.use { it.readBytes().take(16_384).toByteArray().decodeToString() }
        val value = Json.parseToJsonElement(body).jsonObject
        LatestBuild(
            version = value["version"]?.jsonPrimitive?.contentOrNull ?: return@withContext null,
            build = value["build"]?.jsonPrimitive?.intOrNull,
            downloadUrl = value["downloadUrl"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) { null } finally { connection.disconnect() }
}
