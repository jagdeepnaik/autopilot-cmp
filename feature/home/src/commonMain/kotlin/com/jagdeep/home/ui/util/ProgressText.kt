package com.jagdeep.home.ui.util

internal fun progressCheckedLabel(epochMillis: Long): String = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toString().replace('T', ' ').removeSuffix("Z") + " UTC"
