package com.jagdeep.autopilot

import com.jagdeep.home.domain.NoRoutineFiles
import com.jagdeep.home.domain.RoutineFiles

/** Routine files are a desktop feature for now. */
actual fun createRoutineFiles(): RoutineFiles = NoRoutineFiles
