package com.terrainconverter.web

import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.core.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val MULTIPART_UPLOAD_BUFFER_SIZE = 64 * 1024

data class TempUpload(val name: String, val path: Path)

data class ParsedJobMultipart(
    val form: Map<String, String>,
    val bboxMode: String,
    val hgtFiles: List<TempUpload>,
    val baseMbtiles: TempUpload?,
)

data class ParsedMbtilesMultipart(
    val form: Map<String, String>,
    val file: TempUpload?,
)

fun cleanFilename(value: String): String = value.replace('\\', '/').substringAfterLast('/')

private fun saveMultipartFile(part: PartData.FileItem, destination: Path) {
    destination.parent?.let { Files.createDirectories(it) }
    part.provider().use { input ->
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(MULTIPART_UPLOAD_BUFFER_SIZE)
            while (!input.endOfInput) {
                val read = input.readAvailable(buffer, 0, buffer.size)
                if (read > 0) {
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}

suspend fun parseJobMultipart(multipart: MultiPartData, tempRoot: Path): ParsedJobMultipart {
    val form = linkedMapOf<String, String>()
    val hgtFiles = mutableListOf<TempUpload>()
    var baseMbtiles: TempUpload? = null
    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> form[part.name ?: ""] = part.value
            is PartData.FileItem -> {
                val name = cleanFilename(part.originalFileName ?: "upload.bin")
                val destination = tempRoot.resolve(UUID.randomUUID().toString()).resolve(name)
                saveMultipartFile(part, destination)
                val upload = TempUpload(name, destination)
                when (part.name) {
                    "hgt_files" -> hgtFiles += upload
                    "base_mbtiles" -> baseMbtiles = upload
                }
            }
            else -> {}
        }
        part.dispose()
    }
    return ParsedJobMultipart(form, form["bbox_mode"] ?: "auto", hgtFiles, baseMbtiles)
}

suspend fun parseMbtilesMultipart(multipart: MultiPartData, tempRoot: Path): ParsedMbtilesMultipart {
    val form = linkedMapOf<String, String>()
    var file: TempUpload? = null
    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> form[part.name ?: ""] = part.value
            is PartData.FileItem -> if (part.name == "mbtiles") {
                val name = cleanFilename(part.originalFileName ?: "tiles.mbtiles")
                val destination = tempRoot.resolve(UUID.randomUUID().toString()).resolve(name)
                saveMultipartFile(part, destination)
                file = TempUpload(name, destination)
            }
            else -> {}
        }
        part.dispose()
    }
    return ParsedMbtilesMultipart(form, file)
}
