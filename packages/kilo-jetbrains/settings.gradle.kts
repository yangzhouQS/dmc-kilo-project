rootProject.name = "kilo.jetbrains"

include("shared")
include("custom") // custom_change
include("frontend")
include("backend")

pluginManagement {
    includeBuild("build-tasks")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}
