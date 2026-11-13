package com.jagdeep.autopilot.platform

import android.content.Context
import com.jagdeep.logger.Log
import com.jagdeep.logger.initLoggerAndroid

/**
 * The Android application context for the shared code. androidApp's Application calls [init] once
 * in onCreate, before any screen or store is created.
 */
object AndroidPlatform {
    lateinit var context: Context
        private set

    fun init(applicationContext: Context) {
        context = applicationContext
        initLoggerAndroid(applicationContext)
        Log.print("-------------> Android App Start <-------------", "Android App")
    }
}
