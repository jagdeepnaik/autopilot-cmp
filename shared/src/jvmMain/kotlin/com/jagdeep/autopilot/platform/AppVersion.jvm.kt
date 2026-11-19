package com.jagdeep.autopilot.platform

import com.jagdeep.common.config.AppConfig

/** The compact version shown to people: for example, 1.1.0 (131 · e8a8630). */
internal const val APP_VERSION_PROPERTY = "autopilot.version"

/** Complete SemVer build metadata for diagnostics: 1.1.0+build.131.ge8a8630. */
internal const val APP_BUILD_IDENTITY_PROPERTY = "autopilot.buildIdentity"

internal actual val appVersionName: String =
    System.getProperty(APP_VERSION_PROPERTY)?.takeIf { it.isNotBlank() } ?: AppConfig.APP_VERSION_NAME
