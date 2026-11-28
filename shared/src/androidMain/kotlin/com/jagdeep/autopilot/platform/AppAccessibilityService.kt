package com.jagdeep.autopilot.platform

import com.jagdeep.automation.InputAccessibilityService

/**
 * The app's Accessibility service, declared in androidApp's manifest. All of its behaviour is in
 * core:automation; this class gives the shell a name it can see through its one dependency.
 */
class AppAccessibilityService : InputAccessibilityService()
