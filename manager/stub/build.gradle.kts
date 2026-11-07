@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.android.sysupdate"

    compileSdk = androidCompileSdkVersion

    defaultConfig {
        applicationId = "com.android.sysupdate"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}
