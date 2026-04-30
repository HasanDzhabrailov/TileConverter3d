package com.terrainconverter.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val IHDR_TYPE = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
private val IDAT_TYPE = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
private val IEND_TYPE = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
const val PNG_COMPRESSION_LEVEL: Int = 3

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun writeChunk(target: ByteArray, offset: Int, typeBytes: ByteArray, data: ByteArray): Int {
    val crc = CRC32()
    writeInt(target, offset, data.size)
    System.arraycopy(typeBytes, 0, target, offset + 4, typeBytes.size)
    System.arraycopy(data, 0, target, offset + 8, data.size)
    crc.update(typeBytes, 0, typeBytes.size)
    crc.update(data)
    writeInt(target, offset + 8 + data.size, crc.value.toInt())
    return offset + 12 + data.size
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

    val compressedBytes = compressed.toByteArray()
    val ihdr = ByteArray(13)
    writeInt(ihdr, 0, width)
    writeInt(ihdr, 4, height)
    ihdr[8] = 8
    ihdr[9] = 6
    ihdr[10] = 0
    ihdr[11] = 0
    ihdr[12] = 0

    val output = ByteArray(PNG_SIGNATURE.size + 12 + ihdr.size + 12 + compressedBytes.size + 12)
    System.arraycopy(PNG_SIGNATURE, 0, output, 0, PNG_SIGNATURE.size)
    var offset = PNG_SIGNATURE.size
    offset = writeChunk(output, offset, IHDR_TYPE, ihdr)
    offset = writeChunk(output, offset, IDAT_TYPE, compressedBytes)
    writeChunk(output, offset, IEND_TYPE, ByteArray(0))
    return output
}
