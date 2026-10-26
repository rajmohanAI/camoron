import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.camoron.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.camoron.app"
        minSdk = 26
        targetSdk = 35
        // CI numbers each build, so the phone's App info shows which build is installed.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared debug key so CI builds install over each other. Debug only; release signing is separate.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // A fast build for trying on a phone: optimised like a release (not debuggable, so the app
        // runs at full speed) but signed with the shared debug key so it installs over CI builds.
        create("fast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The face and hair models are memory-mapped straight from the APK, which needs them uncompressed.
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.data.camera)
    implementation(projects.data.media)
    implementation(projects.data.processing)
    implementation(projects.data.settings)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.camera)
    implementation(projects.feature.settings)
    implementation(projects.feature.editor)

    implementation(libs.androidx.core.ktx)
    // Applies the libraries' start-up profiles (Compose and others), so screens are compiled ahead of time.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
