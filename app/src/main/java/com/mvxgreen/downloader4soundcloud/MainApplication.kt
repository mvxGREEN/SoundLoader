package com.mvxgreen.downloader4soundcloud

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        // Replaces FirebaseApp.InitializeApp(MainActivity.ActivityCurrent) from MAUI
        FirebaseApp.initializeApp(this)

        // Initialize AdMob
        // Replaces the LoadAdmob() call from MainActivity.cs in MAUI
        MobileAds.initialize(this) { initializationStatus ->
            // Optional: Handle initialization complete
        }

        // You can also trigger directory preparation here if you want it done
        // strictly at app launch, rather than Activity creation.
        // SoundLoader.prepareFileDirs()
    }
}