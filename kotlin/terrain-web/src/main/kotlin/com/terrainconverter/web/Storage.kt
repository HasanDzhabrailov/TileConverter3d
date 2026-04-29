package com.terrainconverter.web

import io.ktor.http.content.PartData
import io.ktor.utils.io.core.readBytes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

data class JobPaths(
    val root: Path,
    val uploads: Path,
    val inputs: Path,
    val outputs: Path,
    val terrainRoot: Path,
    val terrainMbtiles: Path,
    val tilejson: Path,
    val stylejson: Path,
    val baseMbtiles: Path,
)

data class TilesetPaths(
    val root: Path,
    val mbtiles: Path,
)

class Storage(val root: Path) {
    private val jobsRoot = root.resolve("jobs")
    private val tilesetsRoot = root.resolve("tilesets")

    init {
        jobsRoot.createDirectories()
        tilesetsRoot.createDirectories()
    }

    fun pathsFor(jobId: String): JobPaths {
        val root = jobsRoot.resolve(jobId)
        val uploads = root.resolve("uploads")
        val inputs = root.resolve("inputs")
        val outputs = root.resolve("outputs")
        val terrainRoot = outputs.resolve("terrain")
        listOf(root, uploads, inputs, outputs, terrainRoot).forEach { it.createDirectories() }
        return JobPaths(
            root = root,
            uploads = uploads,
            inputs = inputs,
            outputs = outputs,
            terrainRoot = terrainRoot,
            terrainMbtiles = outputs.resolve("terrain-rgb.mbtiles"),
            tilejson = outputs.resolve("tiles.json"),
            stylejson = outputs.resolve("style.json"),
            baseMbtiles = uploads.resolve("base.mbtiles"),
        )
    }

    fun tilesetPathsFor(tilesetId: String): TilesetPaths {
        val root = tilesetsRoot.resolve(tilesetId)
        root.createDirectories()
        return TilesetPaths(root = root, mbtiles = root.resolve("tiles.mbtiles"))
    }

    suspend fun saveUpload(part: PartData.FileItem, destination: Path) {
        destination.parent?.createDirectories()
        Files.write(destination, part.provider().readBytes())
        part.dispose()
    }

    fun moveFile(source: Path, destination: Path) {
        destination.parent?.createDirectories()
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    fun clearInputs(paths: JobPaths) {
        if (paths.inputs.exists()) {
            Files.walk(paths.inputs)
                .sorted(Comparator.reverseOrder())
                .forEach { if (it != paths.inputs) it.deleteIfExists() }
        }
        paths.inputs.createDirectories()
    }
}
