package com.terrainconverter.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
const val PNG_COMPRESSION_LEVEL: Int = 3

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    val buffer = ByteBuffer.allocate(4 + 4 + data.size + 4).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(data.size)
    buffer.put(typeBytes)
    buffer.put(data)
    buffer.putInt(crc.value.toInt())
    return buffer.array()
}

fun writePngRgba(width: Int, height: Int, rgbaBytes: ByteArray): ByteArray {
    val stride = width * 4
    val rawRows = ByteArray((stride + 1) * height)
    for (row in 0 until height) {
        val rawOffset = row * (stride + 1)
        val rgbaOffset = row * stride
        rawRows[rawOffset] = 0
        System.arraycopy(rgbaBytes, rgbaOffset, rawRows, rawOffset + 1, stride)
    }

    val deflater = Deflater(PNG_COMPRESSION_LEVEL)
    deflater.setInput(rawRows)
    deflater.finish()
    val compressed = ByteArrayOutputStream(rawRows.size)
    val buffer = ByteArray(8192)
    while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        compressed.write(buffer, 0, count)
    }
    deflater.end()

    val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        .putInt(width)
        .putInt(height)
        .put(8)
        .put(6)
        .put(0)
        .put(0)
        .put(0)
        .array()

    return ByteArrayOutputStream().apply {
        write(PNG_SIGNATURE)
        write(pngChunk("IHDR", ihdr))
        write(pngChunk("IDAT", compressed.toByteArray()))
        write(pngChunk("IEND", ByteArray(0)))
    }.toByteArray()
}
