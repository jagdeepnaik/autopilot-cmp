package com.jagdeep.autopilot

import com.jagdeep.logger.Log
import java.awt.Desktop
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.jagdeep.shared.browser.BrowserExtensionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val BROWSER_EXTENSION_FILES = listOf("manifest.json", "background.js", "content.js", "course-automation.js", "course-automation-background.js", "dgs-elearning.js", "dgs-elearning-background.js", "dgs-browser-clicks.js")

internal fun bundledBrowserExtensionVersion(): String = object {}.javaClass
    .getResourceAsStream("/chrome-extension/manifest.json")!!.use {
        Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject.getValue("version").jsonPrimitive.content
    }
private val mutableExtensionStatus = MutableStateFlow(BrowserExtensionStatus(latestVersion = bundledBrowserExtensionVersion()))
actual val browserExtensionStatus = mutableExtensionStatus.asStateFlow()

/** Chrome loads an unpacked extension from a folder, so the app's bundled copy is written to one. */
internal fun writeBrowserExtension(folder: File) {
    folder.mkdirs()
    BROWSER_EXTENSION_FILES.forEach { name ->
        val resource = object {}.javaClass.getResourceAsStream("/chrome-extension/$name")
            ?: throw IOException("Missing $name")
        resource.use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
    }
}

/**
 * Brings the extension folder up to date with this app's bundled copy, rewriting only changed files.
 * Runs at start-up so the folder exists before Chrome is pointed at it, and so an app update also
 * updates the extension; Chrome picks up changes after a reload on its extensions page.
 */
internal fun syncBrowserExtension(folder: File): Int {
    folder.mkdirs()
    var written = 0
    // Publish the manifest last, so an interrupted update does not claim a completed new version.
    BROWSER_EXTENSION_FILES.sortedBy { it == "manifest.json" }.forEach { name ->
        val bundled = object {}.javaClass.getResourceAsStream("/chrome-extension/$name")?.use { it.readBytes() }
            ?: throw IOException("Missing $name")
        val target = File(folder, name)
        if (!target.isFile || !target.readBytes().contentEquals(bundled)) {
            target.writeBytes(bundled)
            written++
        }
    }
    return written
}

internal fun syncBrowserExtensionInBackground() {
    Thread({
        try {
            val written = syncBrowserExtension(desktopDataPaths.chromeExtension())
            mutableExtensionStatus.update { it.copy(folderProblem = null) }
            if (written > 0) Log.i("Extension folder updated ($written files)", "BrowserExtension")
        } catch (e: Exception) {
            mutableExtensionStatus.update { it.copy(folderProblem = "Couldn't update the Chrome extension files. Open Settings → Browser connection and choose Show extension folder to try again.") }
            Log.w("Extension folder not updated: ${e.message}", "BrowserExtension")
        }
    }, "extension-sync").apply { isDaemon = true }.start()
}

actual suspend fun installBrowserExtension(): String? = withContext(Dispatchers.IO) {
    val folder = desktopDataPaths.chromeExtension()
    try {
        syncBrowserExtension(folder)
        mutableExtensionStatus.update { it.copy(folderProblem = null) }
    } catch (e: Exception) {
        mutableExtensionStatus.update { it.copy(folderProblem = "Couldn't update the Chrome extension files. Check folder permissions and free disk space, then try again.") }
        Log.w("Extension copy failed: ${e.message}", "BrowserExtension")
        return@withContext "Couldn't create the extension folder. Check there's free disk space, then try again."
    }
    val shown = runCatching { Desktop.getDesktop().open(folder) }.isSuccess
    if (shown) null else "The extension is in ${folder.absolutePath}."
}
