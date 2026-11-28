package com.jagdeep.automation

import android.content.Context

/**
 * Android start-up for automation. The app calls [init] once with the application context and the
 * service class its manifest declares, before anything asks whether input access is allowed.
 */
object AndroidAutomation {
    @Volatile
    internal var appContext: Context? = null
        private set

    @Volatile
    internal var serviceClass: Class<out InputAccessibilityService> = InputAccessibilityService::class.java
        private set

    fun init(context: Context, service: Class<out InputAccessibilityService>) {
        appContext = context.applicationContext
        serviceClass = service
    }
}

/**
 * On Android, taps reach other apps through [InputAccessibilityService], which the user turns on in
 * Settings → Accessibility. Its floating controls stand in for the mouse pointer and the hot keys.
 */
actual fun createDesktopAutomation(): DesktopAutomation = AndroidDesktopAutomation
