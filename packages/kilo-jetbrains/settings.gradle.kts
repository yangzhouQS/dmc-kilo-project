// custom_change - new file
rootProject.name = "kilo.jetbrains"

include("shared")
include("custom")
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
