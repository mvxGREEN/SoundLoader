package com.mvxgreen.downloader4soundcloud

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Context for SoundLoader (CRITICAL FIX)
        SoundLoader.appContext = this

        // Ensure temp dirs exist
        SoundLoader.prepareFileDirs()
    }
}