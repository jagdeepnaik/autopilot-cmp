package com.jagdeep.autopilot

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.home.domain.AssessmentAlerts

internal actual fun withAlertSound(automation: DesktopAutomation): DesktopAutomation = automation
internal actual fun createAssessmentAlerts(automation: DesktopAutomation): AssessmentAlerts = AssessmentAlerts { automation.playAlert() }
