package com.jagdeep.autopilot.platform

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.home.domain.AssessmentAlerts

/** Adds app-owned audible alerts without changing shared platform automation. */
internal expect fun withAlertSound(automation: DesktopAutomation): DesktopAutomation
internal expect fun createAssessmentAlerts(automation: DesktopAutomation): AssessmentAlerts
