package com.jagdeep.autopilot

/** Fixed by the public key in the extension's manifest; the link answers only this extension. */
internal const val BROWSER_EXTENSION_ID = "ebilomdffnbhihdfgkeienfcbceomkap"
internal const val BROWSER_EXTENSION_ORIGIN = "chrome-extension://$BROWSER_EXTENSION_ID"

/** Must match host_permissions in the extension's manifest and BASE in its background.js. */
internal const val BROWSER_LINK_PORT = 47613

/** Puts the extension in a folder and shows it. Returns a problem to explain, or null when done. */
expect suspend fun installBrowserExtension(): String?

/** Bundled version and any failure to update the unpacked extension folder. */
expect val browserExtensionStatus: kotlinx.coroutines.flow.StateFlow<com.jagdeep.shared.browser.BrowserExtensionStatus>
