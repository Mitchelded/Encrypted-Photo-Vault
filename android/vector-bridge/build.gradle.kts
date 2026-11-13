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
        versionCode = 2
        versionName = "0.2.0"
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
