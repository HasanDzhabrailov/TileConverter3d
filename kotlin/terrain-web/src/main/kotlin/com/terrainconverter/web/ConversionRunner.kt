package com.terrainconverter.web

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.ConversionOptions
import com.terrainconverter.core.runConversion as runTerrainConversion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class ConversionRequest(
    val settings: Settings,
    val jobId: String,
    val options: JobOptions,
    val paths: JobPaths,
    val baseUrl: String,
    val log: (String) -> Unit,
    val verboseLogging: Boolean = true,
)

data class ConversionOutcome(
    val bounds: BBox,
    val tileCount: Int,
)

suspend fun runConversionJob(request: ConversionRequest): ConversionOutcome {
    val coroutineContext = currentCoroutineContext()
    val checkedLog: (String) -> Unit = { line ->
        coroutineContext.ensureActive()
        request.log(line)
    }
    val overallStartTime = System.currentTimeMillis()

    val inputPaths = prepareHgtInputs(request.paths, request.verboseLogging, checkedLog)

    if (request.verboseLogging) {
        checkedLog("=".repeat(50))
        checkedLog("Starting terrain conversion job: ${request.jobId}")
        checkedLog("=".repeat(50))
        checkedLog("[INPUT] Prepared ${inputPaths.size} HGT input(s)")
        inputPaths.forEachIndexed { index, path ->
            checkedLog("[INPUT]   ${index + 1}. ${path.name}")
        }

        checkedLog("[PARAMS] Conversion settings:")
        checkedLog("[PARAMS]   Min Zoom: ${request.options.minzoom}")
        checkedLog("[PARAMS]   Max Zoom: ${request.options.maxzoom}")
        checkedLog("[PARAMS]   Tile Size: ${request.options.tileSize}px")
        checkedLog("[PARAMS]   Scheme: ${request.options.scheme}")
        checkedLog("[PARAMS]   Encoding: ${request.options.encoding}")
        checkedLog("[PARAMS]   Workers: ${Runtime.getRuntime().availableProcessors() - 1}")

        if (request.options.bbox != null) {
            checkedLog("[PARAMS]   BBOX (manual): west=${request.options.bbox.west}, south=${request.options.bbox.south}, east=${request.options.bbox.east}, north=${request.options.bbox.north}")
        } else {
            checkedLog("[PARAMS]   BBOX: auto-detected from input data")
        }

        if (request.paths.baseMbtiles.exists()) {
            checkedLog("[PARAMS]   Base MBTiles: ${request.paths.baseMbtiles.fileName}")
        }

        checkedLog("[OUTPUT] Output paths:")
        checkedLog("[OUTPUT]   Tiles: ${request.paths.terrainRoot}")
        checkedLog("[OUTPUT]   MBTiles: ${request.paths.terrainMbtiles}")
        checkedLog("[OUTPUT]   TileJSON: ${request.paths.tilejson}")
        checkedLog("[OUTPUT]   Style: ${request.paths.stylejson}")
        checkedLog("[CONVERT] Starting conversion process...")
    }

    val conversionStart = System.currentTimeMillis()
    val result = runTerrainConversion(
        ConversionOptions(
            inputs = inputPaths,
            outputMbtiles = request.paths.terrainMbtiles,
            tileRoot = request.paths.terrainRoot,
            tileJson = request.paths.tilejson,
            styleJson = request.paths.stylejson,
            tilesUrl = "${request.baseUrl}/api/jobs/${request.jobId}/terrain/{z}/{x}/{y}.png",
            minZoom = request.options.minzoom,
            maxZoom = request.options.maxzoom,
            bbox = request.options.bbox?.let { Bounds(it.west, it.south, it.east, it.north) },
            tileSize = request.options.tileSize,
            scheme = request.options.scheme,
            encoding = request.options.encoding,
            progress = if (request.verboseLogging) checkedLog else null,
            cancellationCheck = { coroutineContext.ensureActive() },
        )
    )
    val conversionTime = System.currentTimeMillis() - conversionStart

    if (request.verboseLogging) {
        checkedLog("[CONVERT] Conversion completed in ${conversionTime}ms")
    }

    val tilejsonText = request.paths.tilejson.readText()
    val bounds = parseBoundsFromTilejson(tilejsonText)
    writeJobDocuments(
        jobId = request.jobId,
        options = request.options,
        bounds = bounds,
        tilejsonPath = request.paths.tilejson,
        stylejsonPath = request.paths.stylejson,
        hasBaseMbtiles = request.paths.baseMbtiles.exists(),
    )

    val actualTileCount = countRenderedTiles(request.paths.terrainRoot)
    val totalTime = System.currentTimeMillis() - overallStartTime

    if (request.verboseLogging) {
        checkedLog("[DOCS] Generated TileJSON and Style JSON")
        checkedLog("=".repeat(50))
        checkedLog("[SUMMARY] Conversion completed successfully")
        checkedLog("[SUMMARY]   Tiles generated: $actualTileCount")
        checkedLog("[SUMMARY]   Bounds: west=${bounds.west}, south=${bounds.south}, east=${bounds.east}, north=${bounds.north}")
        checkedLog("[SUMMARY]   Total time: ${totalTime}ms")
        if (actualTileCount > 0) {
            checkedLog("[SUMMARY]   Average: ${totalTime / actualTileCount}ms per tile")
        }
        checkedLog("=".repeat(50))
    }

    return ConversionOutcome(bounds = bounds, tileCount = actualTileCount)
}

fun prepareHgtInputs(paths: JobPaths, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    val inputs = mutableListOf<Path>()

    Files.list(paths.uploads).use { uploads ->
        uploads.sorted().forEach { upload ->
            if (upload.toFile().name == "base.mbtiles") {
                if (verboseLogging) log?.invoke("[INPUT] Skipping base.mbtiles (used as base map)")
                return@forEach
            }
            val extracted = extractInput(upload, paths.inputs, verboseLogging, log)
            inputs.addAll(extracted)
        }
    }

    require(inputs.isNotEmpty()) { "No HGT files were provided. Please upload at least one .hgt file or a .zip containing .hgt files." }
    return inputs
}

private fun extractInput(source: Path, targetDir: Path, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    return when (source.extension.lowercase()) {
        "zip" -> {
            if (verboseLogging) log?.invoke("[ZIP] Extracting archive: ${source.fileName}")
            extractZipInputs(source, targetDir, verboseLogging, log)
        }
        "hgt" -> {
            if (verboseLogging) log?.invoke("[HGT] Using file: ${source.fileName}")
            listOf(source)
        }
        else -> {
            if (verboseLogging) log?.invoke("[SKIP] Unsupported file type: ${source.fileName}")
            emptyList()
        }
    }
}

private fun extractZipInputs(source: Path, targetDir: Path, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    val extracted = mutableListOf<Path>()
    var entryCount = 0
    ZipInputStream(Files.newInputStream(source)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            if (entry.isDirectory) continue
            if (!entry.name.lowercase().endsWith(".hgt")) {
                if (verboseLogging) log?.invoke("[ZIP]   Skipping non-HGT file: ${entry.name}")
                continue
            }
            val destination = targetDir.resolve(cleanFilename(entry.name))
            destination.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(destination).use { output -> zip.copyTo(output) }
            extracted.add(destination)
        }
    }
    if (verboseLogging) log?.invoke("[ZIP]   Scanned $entryCount entries, extracted ${extracted.size} HGT file(s)")
    return extracted
}

private fun parseBoundsFromTilejson(jsonText: String): BBox {
    val match = Regex("\"bounds\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(jsonText)
        ?: throw IllegalStateException("Missing bounds in tiles.json")
    val values = match.groupValues[1].split(',').map { it.trim().toDouble() }
    return BBox(values[0], values[1], values[2], values[3])
}

private fun countRenderedTiles(terrainRoot: Path): Int {
    Files.walk(terrainRoot).use { paths ->
        return paths.filter { it.isRegularFile() && it.name.endsWith(".png") }.count().toInt()
    }
}
