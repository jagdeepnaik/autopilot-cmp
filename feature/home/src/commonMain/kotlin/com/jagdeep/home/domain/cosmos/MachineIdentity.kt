package com.jagdeep.home.domain.cosmos

import com.jagdeep.home.domain.Sha256

/**
 * Where this computer's own identifier comes from (macOS `IOPlatformUUID`, Windows `MachineGuid`,
 * Linux `/etc/machine-id`, Android `ANDROID_ID`). The raw value never leaves the computer and is never
 * logged: only [MachineIdentity.machineId], its hash, is sent to Cosmos.
 */
interface MachineIdentifierSource {
    /** The contract's platform name (`macos`, `windows`, `linux`, `android`), or null where none applies. */
    val platform: String?

    /** The raw platform identifier, or null when this computer doesn't provide one. */
    fun rawIdentifier(): String?
}

/** This computer as Cosmos knows it. [toString] never prints the ID. */
class MachineIdentity(val machineId: String, val platform: String) {
    override fun toString() = "MachineIdentity(platform=$platform)"
    override fun equals(other: Any?) = other is MachineIdentity && other.machineId == machineId && other.platform == platform
    override fun hashCode() = machineId.hashCode() * 31 + platform.hashCode()

    companion object {
        const val PREFIX = "autopilot-machine-v1:"
        val PLATFORMS = setOf("windows", "macos", "linux", "android")

        /** The contract's `machineId`: lowercase hex SHA-256 of [PREFIX] + the identifier (surrounding spaces trimmed). */
        fun hash(identifier: String): String = Sha256.hex((PREFIX + identifier.trim()).encodeToByteArray())

        /** Null when the platform isn't one Cosmos knows, or the identifier is missing or blank. */
        fun of(source: MachineIdentifierSource): MachineIdentity? {
            val platform = source.platform?.takeIf { it in PLATFORMS } ?: return null
            val raw = runCatching { source.rawIdentifier() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return MachineIdentity(hash(raw), platform)
        }
    }
}

/** For platforms without a machine identifier (and tests): sign-in isn't possible without one. */
object NoMachineIdentifier : MachineIdentifierSource {
    override val platform: String? = null
    override fun rawIdentifier(): String? = null
}
