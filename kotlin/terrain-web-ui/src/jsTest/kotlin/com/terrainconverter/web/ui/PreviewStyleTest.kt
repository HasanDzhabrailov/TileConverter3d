package com.terrainconverter.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewStyleTest {
    @Test
    fun terrainPreviewStylePreservesTmsScheme() {
        val style = buildTerrainPreviewStyle(sampleJob(TileScheme.TMS), null)

        assertEquals("tms", style.sources.terrain.scheme)
    }

    @Test
    fun terrainPreviewStylePreservesXyzScheme() {
        val style = buildTerrainPreviewStyle(sampleJob(TileScheme.XYZ), null)

        assertEquals("xyz", style.sources.terrain.scheme)
    }

    @Test
    fun completedJobBecomesExplicitTerrainPreviewSource() {
        val state = AppState()
        val job = sampleJob(TileScheme.XYZ)

        state.mergeJob(job)

        assertEquals(TerrainPreviewSource.Job(job.id), state.terrainPreviewSource)
        assertEquals(PreviewMode.THREE_D, state.previewMode)
    }

    @Test
    fun demTilesetPreviewSourceOverridesSelectedCompletedJob() {
        val state = AppState()
        val job = sampleJob(TileScheme.XYZ)
        val tileset = sampleTileset(SourceType.RASTER_DEM)

        state.selectedJobId = job.id
        state.jobs = listOf(job)
        state.openTilesetIn3D(tileset)

        assertEquals(TerrainPreviewSource.MbtilesDem(tileset.id), state.terrainPreviewSource)
        assertEquals(PreviewMode.THREE_D, state.previewMode)
    }

    @Test
    fun selectingCompletedJobOverridesPreviousDemPreviewSource() {
        val state = AppState()
        val job = sampleJob(TileScheme.XYZ)
        val tileset = sampleTileset(SourceType.RASTER_DEM)

        state.openTilesetIn3D(tileset)
        state.selectJob(job)

        assertEquals(job.id, state.selectedJobId)
        assertEquals(TerrainPreviewSource.Job(job.id), state.terrainPreviewSource)
        assertEquals(PreviewMode.THREE_D, state.previewMode)
    }

    @Test
    fun bootstrapDoesNotAutoSelectTilesetForPreview() {
        val state = AppState()

        state.applyBootstrap(
            BootstrapData(
                serverInfo = ServerInfo(emptyList()),
                jobs = emptyList(),
                tilesets = listOf(sampleTileset(SourceType.RASTER_DEM)),
                baseSources = listOf(fallbackOpenStreetMapSource),
                storageStats = sampleStorageStats(),
            ),
        )

        assertEquals(null, state.selectedTilesetId)
        assertEquals(null, state.terrainPreviewSource)
        assertEquals(PreviewMode.TWO_D, state.previewMode)
    }

    @Test
    fun appendsSelectedBaseToStyleUrl() {
        assertEquals("/api/jobs/job-1/style?base=cartodb-positron", appendBaseQuery("/api/jobs/job-1/style", "cartodb-positron"))
        assertEquals("/api/jobs/job-1/style?mobile=1&base=none", appendBaseQuery("/api/jobs/job-1/style?mobile=1", "none"))
    }

    @Test
    fun cacheClearConfirmationSummarizesSelectionAndRunningWarning() {
        val confirmation = cacheClearConfirmation(
            stats = sampleStorageStats(),
            completedJobs = true,
            failedJobs = false,
            runningJobs = true,
            uploadedTilesets = true,
            customSources = false,
        )

        assertTrue(confirmation.contains("завершённые процессы: 2"), "Expected 'завершённые процессы: 2' in: $confirmation")
        assertTrue(confirmation.contains("выполняющиеся процессы: 1"), "Expected 'выполняющиеся процессы: 1' in: $confirmation")
        assertTrue(confirmation.contains("загруженные MBTiles: 4"), "Expected 'загруженные MBTiles: 4' in: $confirmation")
        assertTrue(confirmation.contains("будут остановлены"), "Expected 'будут остановлены' in: $confirmation")
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

    private fun sampleTileset(sourceType: SourceType): MbtilesTileset = MbtilesTileset(
        id = "tileset-1",
        filename = "terrain.mbtiles",
        createdAt = "2026-01-01T00:00:00Z",
        tileUrlTemplate = "/api/mbtiles/tileset-1/tiles/{z}/{x}/{y}.png",
        publicTileUrlTemplate = "http://127.0.0.1:8080/api/mbtiles/tileset-1/tiles/{z}/{x}/{y}.png",
        sourceType = sourceType,
        tileFormat = "png",
    )

    private fun sampleStorageStats(): StorageStats = StorageStats(
        totalBytes = 100,
        jobsBytes = 40,
        tilesetsBytes = 50,
        databaseBytes = 10,
        completedJobs = 2,
        failedJobs = 3,
        runningJobs = 1,
        uploadedTilesets = 4,
        customSources = 5,
    )
}
