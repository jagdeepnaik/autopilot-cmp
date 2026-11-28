package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.MachineIdentifierSource
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createMachineIdentifierSource(): MachineIdentifierSource = DesktopMachineIdentifier(System.getProperty("os.name").orEmpty())

/**
 * Reads the desktop's identifier: `ioreg` on macOS, the registry on Windows (JNA, then `reg query`),
 * the machine-id file on Linux. Command output and the identifier are never logged.
 */
internal class DesktopMachineIdentifier(
    private val osName: String,
    private val run: (List<String>) -> String? = ::runQuietly,
    private val readFile: (File) -> String? = { file -> runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull() },
    private val windowsRegistry: () -> String? = ::registryMachineGuid,
) : MachineIdentifierSource {
    override val platform: String? = when {
        osName.startsWith("Mac", ignoreCase = true) -> "macos"
        osName.startsWith("Windows", ignoreCase = true) -> "windows"
        osName.contains("Linux", ignoreCase = true) -> "linux"
        else -> null
    }

    override fun rawIdentifier(): String? = when (platform) {
        "macos" -> run(listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))?.let(::parseIoregPlatformUuid)
        "windows" -> windowsRegistry()?.trim()?.takeIf { it.isNotEmpty() }
            ?: run(listOf("reg", "query", """HKLM\SOFTWARE\Microsoft\Cryptography""", "/v", "MachineGuid", "/reg:64"))?.let(::parseRegMachineGuid)
        "linux" -> LINUX_FILES.firstNotNullOfOrNull { path -> readFile(File(path))?.let(::parseMachineIdFile) }
        else -> null
    }

    companion object {
        private val LINUX_FILES = listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
        private val IOREG_UUID = Regex(""""IOPlatformUUID"\s*=\s*"([^"]+)"""")
        private val REG_GUID = Regex("""MachineGuid\s+REG_SZ\s+(\S+)""")
        private val MACHINE_ID = Regex("[0-9a-fA-F]{32}")

        fun parseIoregPlatformUuid(output: String): String? = IOREG_UUID.find(output)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        fun parseRegMachineGuid(output: String): String? = REG_GUID.find(output)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        /** The first line, when it is the 32 hex digits systemd writes; an empty or "uninitialized" file has none. */
        fun parseMachineIdFile(text: String): String? = text.lineSequence().firstOrNull()?.trim()?.takeIf { MACHINE_ID.matches(it) }
    }
}

private fun registryMachineGuid(): String? = runCatching {
    Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, """SOFTWARE\Microsoft\Cryptography""", "MachineGuid", WinNT.KEY_WOW64_64KEY)
}.getOrNull()

/** Runs a short system command and returns its output, or null on any failure or after 5 seconds. */
private fun runQuietly(command: List<String>): String? = runCatching {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    // Both commands print a few kilobytes at most, well inside the pipe buffer, so waiting first can't block them.
    if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
    process.inputStream.bufferedReader().use { it.readText() }.takeIf { process.exitValue() == 0 }
}.getOrNull()

internal actual suspend fun openInBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
    val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) } ?: return@withContext false
    val desktop = runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }.getOrDefault(false)
    if (desktop && runCatching { Desktop.getDesktop().browse(uri) }.isSuccess) return@withContext true
    // Some Linux desktops don't support java.awt.Desktop browsing.
    val os = System.getProperty("os.name").orEmpty()
    val command = when {
        os.startsWith("Mac", true) -> listOf("open", uri.toString())
        os.startsWith("Windows", true) -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
        else -> listOf("xdg-open", uri.toString())
    }
    runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.isSuccess
}
