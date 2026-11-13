plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.epv.vectorbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.epv.vectorbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
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
}

dependencies {
    // The Xposed API is provided by Vector inside the target app process.
    // Bundling these classes would prevent the hook from working.
    compileOnly("de.robv.android.xposed:api:82")
    // Independent offline inference inside the Google Photos process.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}
