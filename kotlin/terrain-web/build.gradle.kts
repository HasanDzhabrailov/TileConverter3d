import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

repositories {
    maven(url = "https://repo1.maven.org/maven2/")
}

val ktorVersion = "2.3.12"

dependencies {
    implementation(project(":terrain-core"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.terrainconverter.web.TerrainWebServerKt"
    applicationName = "terrain-web"
    applicationDefaultJvmArgs = listOf("-Xms512m", "-Xmx4g")
}

tasks.test {
    useJUnitPlatform()
}

val syncFrontendDist by tasks.registering(GradleBuild::class) {
    group = "distribution"
    description = "Builds and syncs the Kotlin/JS web UI assets served by Ktor"
    dir = file("../terrain-web-ui")
    tasks = listOf("syncFrontendDist")
}

tasks.named<JavaExec>("run") {
    dependsOn(syncFrontendDist)
    workingDir = rootProject.projectDir
    environment("TERRAIN_WEB_FRONTEND_DIST", rootProject.file("web/frontend/dist").absolutePath)
}

tasks.named("installDist") {
    dependsOn(syncFrontendDist)
}
