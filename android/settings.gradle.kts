pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "anmitsu"
include(":core", ":app")
// Pinned upstream converter adapter; :app uses it only behind the debug-only, default-off OSS switch.
include(":oss-converter")
