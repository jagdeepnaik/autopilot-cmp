package com.jagdeep.autopilot

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(applicationContext)
    }
}
