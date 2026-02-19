plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.mvxgreen.downloader4soundcloud"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.mvxgreen.downloader4soundcloud"
        minSdk = 24
        targetSdk = 36
        versionCode = 113
        versionName = "5.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // HTML Parsing (Replaces AngleSharp)
    implementation(libs.jsoup)

    // ID3 Tags (Replaces TagLibSharp)
    implementation(libs.jaudiotagger)

    // Google Play Services (AdMob & Billing)
    implementation(libs.play.services.ads)
    implementation(libs.billing)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.google.firebase.crashlytics)
    implementation(libs.google.firebase.analytics)

    // Coroutines (Async/Await replacement)
    implementation(libs.kotlinx.coroutines.android)

    // Image Loading (For Thumbnails)
    implementation(libs.glide)
}