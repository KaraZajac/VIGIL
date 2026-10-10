plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.soulstone.vigil"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.soulstone.vigil"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.1.13"
    }

    // Fixed debug keystore committed to the repo (a debug key is non-secret — its
    // password is the well-known "android") so CI and local builds sign
    // identically and updates install in place. Mirrors OVERWATCH.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The detection-evaluation tests print result tables (latency, FP/carry-day,
    // the rotation sweep); surface them in the Gradle/CI log rather than burying
    // them in the HTML report.
    testOptions {
        unitTests.all {
            it.testLogging { showStandardStreams = true }
        }
    }
}

// Export the Room schema so real migrations can be written and tested. Until they
// exist the database still falls back to destructive migration, but the user's
// allowlist is now kept outside Room (see Settings) so a wipe no longer silently
// un-approves their own trackers.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.play.services.location)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
