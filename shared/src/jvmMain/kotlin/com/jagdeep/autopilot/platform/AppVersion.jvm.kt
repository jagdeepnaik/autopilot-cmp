package com.jagdeep.autopilot.platform

import com.jagdeep.common.config.AppConfig

/** Desktop builds pass SemVer plus build number and commit so the exact source is visible. */
internal const val APP_VERSION_PROPERTY = "autopilot.version"

internal actual val appVersionName: String =
    System.getProperty(APP_VERSION_PROPERTY)?.takeIf { it.isNotBlank() } ?: AppConfig.APP_VERSION_NAME
