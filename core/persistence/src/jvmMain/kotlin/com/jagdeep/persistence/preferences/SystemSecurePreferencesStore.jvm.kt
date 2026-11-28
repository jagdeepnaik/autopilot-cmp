package com.jagdeep.persistence.preferences

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One secret string held by the operating system's secure storage. Implementations never write the
 * value to ordinary files in plain text, logs, process arguments or ordinary preferences.
 */
interface SecretBackend {
    /** The stored value, or null when nothing is stored. Throws [IOException] when storage can't be read. */
    fun read(): String?

    /** Throws [IOException] when the value couldn't be stored. */
    fun write(value: String)

    fun delete()
}

/**
 * [SecurePreferencesStore] backed by platform secure storage: the macOS Keychain, Windows DPAPI
 * (encrypted for the signed-in Windows user) or the Linux Secret Service.
 */
class SystemSecurePreferencesStore<T>(
    private val backend: SecretBackend,
    private val serializer: KSerializer<T>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SecurePreferencesStore<T> {
    private val mutex = Mutex()
    // Wrapped so "not loaded yet" (null) differs from "loaded, nothing stored" (Holder(null)).
    private val current = MutableStateFlow<Holder<T>?>(null)

    override val data: Flow<T?> = current.onStart { load() }.filterNotNull().map { it.value }

    override suspend fun save(value: T) = mutex.withLock {
        withContext(Dispatchers.IO) { backend.write(json.encodeToString(serializer, value)) }
        current.value = Holder(value)
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { backend.delete() } }
        current.value = Holder(null)
    }

    private suspend fun load() = mutex.withLock {
        if (current.value != null) return@withLock
        val stored = withContext(Dispatchers.IO) { runCatching { backend.read() }.getOrNull() }
        current.value = Holder(stored?.let { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() })
    }

    private class Holder<T>(val value: T?)
}

/**
 * The secure storage of this desktop, or null where none is available. There is deliberately no
 * fallback to ordinary files or preferences.
 *
 * @param service names the item in the Keychain or Secret Service, for example the app name.
 * @param account the item's account, for example the feature that owns the secret.
 * @param folder where the Windows DPAPI-encrypted file is kept.
 */
fun systemSecretBackend(service: String, account: String, folder: File): SecretBackend? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("mac") -> MacKeychainSecretBackend(service, account)
        os.startsWith("windows") -> WindowsDpapiSecretBackend(File(folder, safeName("$service-$account") + ".dpapi"))
        os.contains("linux") && File("/usr/bin/secret-tool").canExecute() -> LinuxSecretToolBackend(service, account)
        else -> null
    }
}

private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

/**
 * macOS Keychain through the system `security` tool. The value is sent on standard input in
 * interactive mode, never as a process argument, and is Base64-encoded so it needs no quoting.
 */
class MacKeychainSecretBackend(private val service: String, private val account: String) : SecretBackend {
    init { require(service.none { it == '"' || it == '\n' } && account.none { it == '"' || it == '\n' }) }

    override fun read(): String? {
        val result = run(listOf("find-generic-password", "-s", service, "-a", account, "-w"))
        if (result.exitCode == ITEM_NOT_FOUND) return null
        if (result.exitCode != 0) throw IOException("Keychain item could not be read")
        return runCatching { String(Base64.getDecoder().decode(result.output.trim()), Charsets.UTF_8) }.getOrNull()
    }

    override fun write(value: String) {
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val command = "add-generic-password -U -s \"$service\" -a \"$account\" -w \"$encoded\"\n"
        run(listOf("-i"), input = command)
        // Interactive mode reports success even when a command fails, so check by reading back.
        if (read() != value) throw IOException("Keychain item could not be saved")
    }

    override fun delete() {
        run(listOf("delete-generic-password", "-s", service, "-a", account))
    }

    private fun run(arguments: List<String>, input: String? = null): ProcessResult {
        val process = ProcessBuilder(listOf("/usr/bin/security") + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Keychain did not respond")
        }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object { const val ITEM_NOT_FOUND = 44 }
}

/** Windows DPAPI: the file holds bytes only the signed-in Windows user can decrypt. */
class WindowsDpapiSecretBackend(private val file: File) : SecretBackend {
    override fun read(): String? {
        if (!file.isFile) return null
        return try { String(Crypt32Util.cryptUnprotectData(file.readBytes()), Charsets.UTF_8) }
        catch (error: Exception) { throw IOException("Protected item could not be read") }
    }

    override fun write(value: String) {
        val protected = try { Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8)) }
        catch (error: Exception) { throw IOException("Protected item could not be saved") }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeBytes(protected)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete() { file.delete() }
}

/** Linux Secret Service through `secret-tool`; the value is passed on standard input. */
class LinuxSecretToolBackend(private val service: String, private val account: String) : SecretBackend {
    override fun read(): String? {
        val process = ProcessBuilder("/usr/bin/secret-tool", "lookup", "service", service, "account", account)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw IOException("Secret Service did not respond") }
        return output.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }

    override fun write(value: String) {
        val process = ProcessBuilder("/usr/bin/secret-tool", "store", "--label=$service", "service", service, "account", account)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(value.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) throw IOException("Secret Service item could not be saved")
    }

    override fun delete() {
        ProcessBuilder("/usr/bin/secret-tool", "clear", "service", service, "account", account)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(15, TimeUnit.SECONDS)
    }
}
