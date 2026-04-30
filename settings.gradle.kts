pluginManagement {
    repositories {
        gradlePluginPortal()
        maven(url = "https://repo1.maven.org/maven2/")
    }
}

rootProject.name = "terrain-converter-project"

include("terrain-core")
project(":terrain-core").projectDir = file("kotlin/terrain-core")

include("terrain-cli")
project(":terrain-cli").projectDir = file("kotlin/terrain-cli")

include("terrain-web")
project(":terrain-web").projectDir = file("kotlin/terrain-web")
