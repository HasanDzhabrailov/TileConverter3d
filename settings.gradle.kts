pluginManagement {
    repositories {
        gradlePluginPortal()
        maven(url = "https://repo1.maven.org/maven2/")
        maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "terrain-converter-project"

include("terrain-core")
project(":terrain-core").projectDir = file("kotlin/terrain-core")

include("terrain-cli")
project(":terrain-cli").projectDir = file("kotlin/terrain-cli")

include("terrain-web")
project(":terrain-web").projectDir = file("kotlin/terrain-web")

include("terrain-web-ui")
project(":terrain-web-ui").projectDir = file("kotlin/terrain-web-ui")
