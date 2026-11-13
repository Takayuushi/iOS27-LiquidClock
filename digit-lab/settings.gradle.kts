pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LiquidClockDigitLab"
include(":app", ":clock-renderer")
project(":clock-renderer").projectDir = file("../clock-renderer")
