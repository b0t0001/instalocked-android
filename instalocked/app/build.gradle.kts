plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.instalocked"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.instalocked"
        minSdk = 26
        targetSdk = 30
        versionCode = 9
        versionName = "5.1-quotafix"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // This app is sideloaded to one device and will never be distributed
        // through Google Play, so the Play targetSdk floor does not apply.
        // targetSdk 30 is a deliberate match for the target phone (Android 11)
        // and avoids the Android 12+ behaviour changes we don't want.
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
    }
}

dependencies {
    // Deliberately no AndroidX, no Material, no Compose, no Room.
    // Kotlin stdlib only. Keeps the APK in the low hundreds of KB
    // and removes every dependency that could break the build.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
