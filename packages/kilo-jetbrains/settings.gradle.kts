rootProject.name = "kilo.jetbrains"

include("shared")`ninclude("custom") // custom_change`ninclude("custom") // custom_change
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
