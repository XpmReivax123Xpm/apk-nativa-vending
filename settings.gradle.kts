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
    }
}

rootProject.name = "apk-nativa-vending"

include(
    ":app",
    ":domain",
    ":data",
    ":integration-backend",
    ":integration-serial",
    ":kiosk-device",
)

