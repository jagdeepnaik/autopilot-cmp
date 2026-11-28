package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.MachineIdentifierSource
import com.jagdeep.home.domain.cosmos.NoMachineIdentifier
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

// The contract has no iOS machine identifier; Cosmos sync isn't offered on iOS.
internal actual fun createMachineIdentifierSource(): MachineIdentifierSource = NoMachineIdentifier

internal actual suspend fun openInBrowser(url: String): Boolean {
    val link = NSURL.URLWithString(url) ?: return false
    if (link.scheme != "https" && link.scheme != "http") return false
    UIApplication.sharedApplication.openURL(link, emptyMap<Any?, Any>(), null)
    return true
}
