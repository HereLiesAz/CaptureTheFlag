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
        maven("https://raw.githubusercontent.com/HereLiesAz/AzNavRail/update-maven/") {
            content {
                includeModule("com.github.HereLiesAz.AzNavRail", "aznavrail-updates")
                includeModule("com.github.HereLiesAz.AzNavRail", "aznavrail-updates-play")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "CaptureTheFlag"
include(":app", ":shared", ":node")
