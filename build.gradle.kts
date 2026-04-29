plugins {
    base
    kotlin("jvm") version "1.9.23" apply false
    kotlin("plugin.serialization") version "1.9.23" apply false
}

allprojects {
    group = "com.terrainconverter"
    version = "0.1.0"
}
