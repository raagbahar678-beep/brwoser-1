plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The workflow passes -PcopyId=1, 2, 3 ... so every copy gets its own
// package name (com.example.myapp.c1, c2, ...) and its own launcher label (1, 2, 3 ...).
val copyId: String = (project.findProperty("copyId") as String?) ?: "1"

android {
    namespace = "com.example.myapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapp.c" + copyId
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resValue("string", "app_name", copyId)
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
