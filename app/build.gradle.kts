plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mvxgreen.downloader4soundcloud"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mvxgreen.downloader4soundcloud"
        minSdk = 24
        targetSdk = 37
        versionCode = 117
        versionName = "5.3.1"

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

    // Coroutines (Async/Await replacement)
    implementation(libs.kotlinx.coroutines.android)

    // Image Loading (For Thumbnails)
    implementation(libs.glide)

    implementation(libs.androidx.lifecycle.runtime.ktx)
}