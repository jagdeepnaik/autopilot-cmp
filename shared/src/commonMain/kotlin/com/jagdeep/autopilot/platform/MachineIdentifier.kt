package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.MachineIdentifierSource

/**
 * This computer's own identifier for Cosmos machine approval (macOS IOPlatformUUID, Windows MachineGuid,
 * Linux /etc/machine-id, Android ANDROID_ID). The raw value is only hashed, never sent or logged.
 */
internal expect fun createMachineIdentifierSource(): MachineIdentifierSource

/** Opens [url] in the default browser; false when this platform couldn't. The URL is never logged. */
internal expect suspend fun openInBrowser(url: String): Boolean
