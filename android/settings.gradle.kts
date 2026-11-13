pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed API 82 (compileOnly, provided at runtime by Vector).
        maven("https://api.xposed.info/")
        maven("https://repository-cdn.liferay.com/nexus/content/repositories/gradle-plugins/")
    }
}
rootProject.name = "EncryptedPhotoVault"
include(":app", ":vector-bridge")
