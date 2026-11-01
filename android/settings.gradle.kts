pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "subvox-android"

// Pure-Kotlin decoder: builds and tests anywhere with a JDK.
include(":core")

// The Android probe app needs the Android SDK. Include it only when one is configured,
// so `./gradlew :core:test` keeps working on machines (and CI) without it.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) include(":app")
