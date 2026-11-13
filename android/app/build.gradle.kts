plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "io.github.epv.vault"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.epv.vault"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.6.0"
    }

    // Use the same private keystore in every CI build once the repository
    // secrets have been configured. Without it debug builds remain ephemeral.
    val persistentStoreFile = System.getenv("EPV_SIGNING_STORE_FILE")
    val persistentStorePassword = System.getenv("EPV_SIGNING_STORE_PASSWORD")
    val persistentKeyAlias = System.getenv("EPV_SIGNING_KEY_ALIAS")
    val persistentKeyPassword = System.getenv("EPV_SIGNING_KEY_PASSWORD")
    val persistentSigningAvailable = listOf(
        persistentStoreFile, persistentStorePassword, persistentKeyAlias,
        persistentKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (persistentSigningAvailable) {
            create("epvPersistent") {
                storeFile = file(persistentStoreFile!!)
                storePassword = persistentStorePassword!!
                keyAlias = persistentKeyAlias!!
                keyPassword = persistentKeyPassword!!
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (persistentSigningAvailable) {
                signingConfig = signingConfigs.getByName("epvPersistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}
