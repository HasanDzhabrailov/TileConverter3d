package com.terrainconverter.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewStyleTest {
    @Test
    fun terrainPreviewStylePreservesTmsScheme() {
        val style = buildTerrainPreviewStyle(sampleJob(TileScheme.TMS), PreviewBase.NONE)

        assertEquals("tms", style.sources.terrain.scheme)
    }

    @Test
    fun terrainPreviewStylePreservesXyzScheme() {
        val style = buildTerrainPreviewStyle(sampleJob(TileScheme.XYZ), PreviewBase.NONE)

        assertEquals("xyz", style.sources.terrain.scheme)
    }

    private fun sampleJob(scheme: TileScheme): Job = Job(
        id = "job-1",
        status = JobStatus.COMPLETED,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        options = JobOptions(
            bboxMode = BBoxMode.AUTO,
            minzoom = 8,
            maxzoom = 12,
            tileSize = 256,
            scheme = scheme,
            encoding = TerrainEncoding.MAPBOX,
        ),
        hasBaseMbtiles = false,
        artifacts = JobArtifacts(),
        result = JobResult(),
    )
}
