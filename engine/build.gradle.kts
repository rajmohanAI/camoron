import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The photo optimisation engine: plain Kotlin with no Android or camera code, so the camera, the
// editor, a background job (and later another platform) can all run the same restoration.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
