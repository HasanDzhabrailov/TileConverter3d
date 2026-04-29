package com.terrainconverter.core

import kotlin.test.Test

class TerrainCoreGradleParityTest {
    @Test
    fun runsLegacyParitySuiteUnderGradle() {
        TerrainCoreTests.runAll()
    }
}
