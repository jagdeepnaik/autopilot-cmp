package com.jagdeep.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * The Accessibility service that lets the app tap in other apps. It reads only which app is in
 * front (from window changes); it never reads screen contents or what is typed. The app declares a
 * subclass in its manifest, with the gesture permission and no window-content access, and passes
 * that class to [AndroidAutomation.init].
 */
open class InputAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidDesktopAutomation.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        AndroidDesktopAutomation.onWindowChanged(event.packageName?.toString(), event.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AndroidDesktopAutomation.onScreenChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AndroidDesktopAutomation.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AndroidDesktopAutomation.detach(this)
        super.onDestroy()
    }
}
