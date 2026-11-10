package com.jagdeep.autopilot

import com.jagdeep.home.domain.RoutineFiles

/** The system's Save and Open dialogs for routine files, where the platform has them. */
expect fun createRoutineFiles(): RoutineFiles
