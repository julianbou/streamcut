pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Do NOT rename this to the fork's name. Compose Resources derives its
// generated package from it, so changing "Nuvio" here renames
// nuvio.composeapp.generated.resources and breaks the import in every file that
// uses a string or drawable resource. The fork's user-facing name lives in
// gradle.properties (fork.appName) and reaches the UI through ForkBranding.
rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":androidApp")
include(":desktopSentry")
if (!System.getProperty("os.name").contains("win", ignoreCase = true)) {
    include(":composeMediaPlayer")
    project(":composeMediaPlayer").projectDir = file("vendor/compose-media-player/mediaplayer")
}
