package com.jagdeep.autopilot
import com.jagdeep.autopilot.platform.AndroidPlatform

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(applicationContext)
    }
}
