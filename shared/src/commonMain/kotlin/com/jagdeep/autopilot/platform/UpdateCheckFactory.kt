package com.jagdeep.autopilot.platform

import com.jagdeep.shared.update.UpdateCheck
import kotlinx.coroutines.CoroutineScope

/**
 * Cosmos's public update check for this app's own platform (no sign-in): compares the published
 * build number with this build's. Desktop and Android; it never shows an error.
 */
internal expect fun createUpdateCheck(scope: CoroutineScope): UpdateCheck
