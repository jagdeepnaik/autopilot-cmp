package com.jagdeep.autopilot.platform

actual suspend fun installBrowserExtension(): String? = "The web page helper works with Chrome on a computer."

actual val browserExtensionStatus: kotlinx.coroutines.flow.StateFlow<com.jagdeep.shared.browser.BrowserExtensionStatus> =
    kotlinx.coroutines.flow.MutableStateFlow(com.jagdeep.shared.browser.BrowserExtensionStatus())
