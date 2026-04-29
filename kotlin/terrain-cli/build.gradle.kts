plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":terrain-core"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.terrainconverter.cli.TerrainConverterCliKt"
    applicationName = "terrain-converter"
}

tasks.test {
    useJUnitPlatform()
}
