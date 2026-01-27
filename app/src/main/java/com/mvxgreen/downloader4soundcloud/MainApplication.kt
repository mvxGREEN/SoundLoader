package com.mvxgreen.downloader4soundcloud

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Context for SoundLoader (CRITICAL FIX)
        SoundLoader.appContext = this

        FirebaseApp.initializeApp(this)
        MobileAds.initialize(this) {}

        // Ensure temp dirs exist
        SoundLoader.prepareFileDirs()
    }
}