package com.jagdeep.autopilot.platform

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jagdeep.home.domain.cosmos.MachineIdentifierSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createMachineIdentifierSource(): MachineIdentifierSource = object : MachineIdentifierSource {
    override val platform = "android"
    override fun rawIdentifier(): String? = runCatching {
        Settings.Secure.getString(AndroidPlatform.context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()
}

internal actual suspend fun openInBrowser(url: String): Boolean = withContext(Dispatchers.Main) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return@withContext false
    runCatching {
        AndroidPlatform.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
