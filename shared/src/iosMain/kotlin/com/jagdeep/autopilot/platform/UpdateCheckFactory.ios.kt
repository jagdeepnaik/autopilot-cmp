package com.jagdeep.autopilot.platform

import com.jagdeep.shared.update.NoUpdateCheck
import com.jagdeep.shared.update.UpdateCheck
import kotlinx.coroutines.CoroutineScope

// iOS builds are installed outside Compass (no installable file is published).
internal actual fun createUpdateCheck(scope: CoroutineScope): UpdateCheck = NoUpdateCheck
