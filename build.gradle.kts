import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    base
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

allprojects {
    group = "com.terrainconverter"
    version = "0.1.0"
}

val useSystemJsToolchain = providers.gradleProperty("terrain.useSystemJsToolchain")
    .map(String::toBoolean)
    .orElse(false)

plugins.withType<NodeJsRootPlugin> {
    extensions.configure<NodeJsRootExtension> {
        download = !useSystemJsToolchain.get()
    }
}

plugins.withType<YarnPlugin> {
    extensions.configure<YarnRootExtension> {
        download = !useSystemJsToolchain.get()
    }
}
