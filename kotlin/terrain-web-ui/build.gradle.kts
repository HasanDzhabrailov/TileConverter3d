import org.gradle.api.tasks.Sync

plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

kotlin {
    js(IR) {
        browser {
            binaries.executable()
            testTask {
                enabled = false
            }
            commonWebpackConfig {
                outputFileName = "terrain-web-ui.js"
            }
        }
        nodejs()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.html.core)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation(npm("maplibre-gl", "4.7.1"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

repositories {
    maven(url = "https://repo1.maven.org/maven2/")
    maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

val productionAssetsDir = layout.buildDirectory.dir("dist/js/productionExecutable")
val frontendDistDir = layout.projectDirectory.dir("../../web/frontend/dist")
val mapLibreCssDir = layout.buildDirectory.dir("js/node_modules/maplibre-gl/dist")

tasks.register<Sync>("syncFrontendDist") {
    group = "distribution"
    description = "Copies Kotlin web UI production assets into web/frontend/dist"
    dependsOn("kotlinNpmInstall", "jsBrowserDistribution")
    from(productionAssetsDir)
    from(mapLibreCssDir) {
        include("maplibre-gl.css")
    }
    into(frontendDistDir)
}
