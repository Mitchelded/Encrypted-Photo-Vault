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
        versionCode = 1
        versionName = "0.1.0"
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
}
