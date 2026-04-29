package com.terrainconverter.cli

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.ConversionOptions
import com.terrainconverter.core.DEFAULT_WORKERS
import com.terrainconverter.core.runConversion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val DEFAULT_OUTPUT_MBTILES = "terrain-rgb.mbtiles"
private const val DEFAULT_TILE_ROOT = "terrain"
private const val DEFAULT_TILEJSON = "terrain/tiles.json"
private const val DEFAULT_STYLE_JSON = "style.json"
private const val DEFAULT_TILES_URL = "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png"
private const val DEFAULT_MIN_ZOOM = 8
private const val DEFAULT_MAX_ZOOM = 12
private const val DEFAULT_TILE_SIZE = 256
private const val DEFAULT_SCHEME = "xyz"
private const val DEFAULT_ENCODING = "mapbox"
private const val DEFAULT_NAME = "terrain-dem"

// Exit codes matching the established CLI contract
private const val EXIT_SUCCESS = 0
private const val EXIT_GENERAL_ERROR = 1
private const val EXIT_VALIDATION_ERROR = 2
private const val EXIT_INPUT_ERROR = 3

data class CliArguments(
    val inputs: List<String>,
    val outputMbtiles: String = DEFAULT_OUTPUT_MBTILES,
    val tileRoot: String = DEFAULT_TILE_ROOT,
    val tileJson: String = DEFAULT_TILEJSON,
    val styleJson: String = DEFAULT_STYLE_JSON,
    val tilesUrl: String = DEFAULT_TILES_URL,
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
    val bbox: List<Double>? = null,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val scheme: String = DEFAULT_SCHEME,
    val encoding: String = DEFAULT_ENCODING,
    val name: String = DEFAULT_NAME,
    val workers: Int = DEFAULT_WORKERS,
)

private class HelpRequested : RuntimeException()
private class ValidationException(message: String) : RuntimeException(message)
private class MissingInputException(message: String) : RuntimeException(message)
private class InputErrorException(message: String) : RuntimeException(message)

private fun usage(programName: String = "terrain-converter"): String = """
Usage: $programName [options] inputs [inputs ...]

Convert HGT/SRTM tiles into MapLibre Terrain-RGB tiles

Arguments:
  inputs                         HGT files or directories

Options:
  -o, --output PATH              Output MBTiles path (default: $DEFAULT_OUTPUT_MBTILES)
  --output-mbtiles PATH          Output MBTiles path (default: $DEFAULT_OUTPUT_MBTILES)
  --tile-root PATH               Output tile directory root (default: $DEFAULT_TILE_ROOT)
  --tilejson PATH                Output TileJSON path (default: $DEFAULT_TILEJSON)
  --style-json PATH              Output style.json path (default: $DEFAULT_STYLE_JSON)
  --tiles-url URL                Public terrain tile URL template (default: $DEFAULT_TILES_URL)
  --minzoom INT                  Minimum output zoom (default: $DEFAULT_MIN_ZOOM)
  --maxzoom INT                  Maximum output zoom (default: $DEFAULT_MAX_ZOOM)
  --bbox WEST SOUTH EAST NORTH   Optional manual output bounds
  --tile-size INT                Output tile size in pixels (default: $DEFAULT_TILE_SIZE)
  --scheme {xyz,tms}             Output TileJSON/style scheme (default: $DEFAULT_SCHEME)
  --encoding {mapbox}            Terrain encoding (default: $DEFAULT_ENCODING)
  --name NAME                    MBTiles metadata name (default: $DEFAULT_NAME)
  --workers INT                  Worker process count for tile rendering (default: $DEFAULT_WORKERS)
  -h, --help                     Show this help message and exit
""".trimIndent()

fun parseCliArgs(argv: List<String>): CliArguments {
    var outputMbtiles = DEFAULT_OUTPUT_MBTILES
    var tileRoot = DEFAULT_TILE_ROOT
    var tileJson = DEFAULT_TILEJSON
    var styleJson = DEFAULT_STYLE_JSON
    var tilesUrl = DEFAULT_TILES_URL
    var minZoom = DEFAULT_MIN_ZOOM
    var maxZoom = DEFAULT_MAX_ZOOM
    var bbox: List<Double>? = null
    var tileSize = DEFAULT_TILE_SIZE
    var scheme = DEFAULT_SCHEME
    var encoding = DEFAULT_ENCODING
    var name = DEFAULT_NAME
    var workers = DEFAULT_WORKERS
    val inputs = ArrayList<String>()

    var index = 0
    while (index < argv.size) {
        when (val arg = argv[index]) {
            "-h", "--help" -> throw HelpRequested()
            "-o", "--output", "--output-mbtiles" -> {
                outputMbtiles = requireValue(argv, ++index, arg)
                index += 1
            }
            "--tile-root" -> {
                tileRoot = requireValue(argv, ++index, arg)
                index += 1
            }
            "--tilejson" -> {
                tileJson = requireValue(argv, ++index, arg)
                index += 1
            }
            "--style-json" -> {
                styleJson = requireValue(argv, ++index, arg)
                index += 1
            }
            "--tiles-url" -> {
                tilesUrl = requireValue(argv, ++index, arg)
                index += 1
            }
            "--minzoom" -> {
                minZoom = requireValue(argv, ++index, arg).toIntOrNull()
                    ?: throw ValidationException("argument --minzoom: invalid int value")
                if (minZoom < 0) throw ValidationException("argument --minzoom: invalid int value")
                index += 1
            }
            "--maxzoom" -> {
                maxZoom = requireValue(argv, ++index, arg).toIntOrNull()
                    ?: throw ValidationException("argument --maxzoom: invalid int value")
                if (maxZoom < 0) throw ValidationException("argument --maxzoom: invalid int value")
                index += 1
            }
            "--bbox" -> {
                val values = ArrayList<Double>(4)
                repeat(4) { offset ->
                    val raw = requireValue(argv, index + 1 + offset, arg)
                    values += raw.toDoubleOrNull()
                        ?: throw ValidationException("argument --bbox: invalid float value: '$raw'")
                }
                bbox = values
                index += 5
            }
            "--tile-size" -> {
                tileSize = requireValue(argv, ++index, arg).toIntOrNull()
                    ?: throw ValidationException("argument --tile-size: invalid int value")
                index += 1
            }
            "--scheme" -> {
                scheme = requireValue(argv, ++index, arg)
                if (scheme != "xyz" && scheme != "tms") {
                    throw ValidationException("argument --scheme: invalid choice: '$scheme' (choose from 'xyz', 'tms')")
                }
                index += 1
            }
            "--encoding" -> {
                encoding = requireValue(argv, ++index, arg)
                if (encoding != "mapbox" && encoding != "terrarium") {
                    throw ValidationException("argument --encoding: invalid choice: '$encoding' (choose from 'mapbox', 'terrarium')")
                }
                index += 1
            }
            "--name" -> {
                name = requireValue(argv, ++index, arg)
                index += 1
            }
            "--workers" -> {
                workers = requireValue(argv, ++index, arg).toIntOrNull()
                    ?: throw ValidationException("argument --workers: invalid int value")
                index += 1
            }
            else -> {
                if (arg.startsWith("--")) {
                    throw ValidationException("unrecognized arguments: $arg")
                }
                inputs += arg
                index += 1
            }
        }
    }

    if (inputs.isEmpty()) {
        throw MissingInputException("the following arguments are required: inputs")
    }

    return CliArguments(
        inputs = inputs,
        outputMbtiles = outputMbtiles,
        tileRoot = tileRoot,
        tileJson = tileJson,
        styleJson = styleJson,
        tilesUrl = tilesUrl,
        minZoom = minZoom,
        maxZoom = maxZoom,
        bbox = bbox,
        tileSize = tileSize,
        scheme = scheme,
        encoding = encoding,
        name = name,
        workers = workers,
    )
}

private fun requireValue(argv: List<String>, index: Int, option: String): String {
    if (index >= argv.size) {
        throw IllegalArgumentException("argument $option: expected one argument")
    }
    return argv[index]
}

private fun validateInputFiles(inputs: List<String>): List<Path> {
    val paths = inputs.map { Path.of(it) }
    for (path in paths) {
        if (!Files.exists(path)) {
            throw InputErrorException("input file not found: '$path' does not exist")
        }
        val fileName = path.fileName.toString().lowercase()
        if (!fileName.endsWith(".hgt")) {
            throw InputErrorException("invalid file extension: '$path' must have .hgt extension")
        }
    }
    return paths
}

private fun CliArguments.toConversionOptions(): ConversionOptions = ConversionOptions(
    inputs = validateInputFiles(inputs),
    outputMbtiles = Path.of(outputMbtiles),
    tileRoot = Path.of(tileRoot),
    tileJson = Path.of(tileJson),
    styleJson = Path.of(styleJson),
    tilesUrl = tilesUrl,
    minZoom = minZoom,
    maxZoom = maxZoom,
    bbox = bbox?.let { Bounds(it[0], it[1], it[2], it[3]) },
    tileSize = tileSize,
    scheme = scheme,
    encoding = encoding,
    name = name,
    workers = workers,
)

fun main(argv: Array<String>) {
    val exitCode = try {
        val args = parseCliArgs(argv.toList())
        val result = runConversion(args.toConversionOptions())
        println("Generated ${result.tileCount} terrain tiles")
        println("MBTiles: ${result.outputMbtiles}")
        println("TileJSON: ${result.tileJson}")
        println("Style: ${result.styleJson}")
        EXIT_SUCCESS
    } catch (_: HelpRequested) {
        println(usage())
        EXIT_SUCCESS
    } catch (error: MissingInputException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(usage())
        EXIT_GENERAL_ERROR
    } catch (error: ValidationException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(usage())
        EXIT_VALIDATION_ERROR
    } catch (error: InputErrorException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(usage())
        EXIT_INPUT_ERROR
    } catch (error: Exception) {
        System.err.println(error.message ?: error.javaClass.name)
        EXIT_GENERAL_ERROR
    }
    if (exitCode != EXIT_SUCCESS) {
        exitProcess(exitCode)
    }
}
