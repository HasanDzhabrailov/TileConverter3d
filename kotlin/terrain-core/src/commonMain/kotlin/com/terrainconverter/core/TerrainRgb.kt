package com.terrainconverter.core

data class Rgb(val red: Int, val green: Int, val blue: Int)

fun encodeElevation(elevationMeters: Double): Rgb {
    var encoded = kotlin.math.round((elevationMeters + 10000.0) * 10.0).toLong()
    encoded = encoded.coerceIn(0L, (256L * 256L * 256L) - 1L)
    return Rgb(
        red = ((encoded shr 16) and 255).toInt(),
        green = ((encoded shr 8) and 255).toInt(),
        blue = (encoded and 255).toInt(),
    )
}

fun decodeElevation(red: Int, green: Int, blue: Int): Double {
    val encoded = (red shl 16) + (green shl 8) + blue
    return -10000.0 + (encoded * 0.1)
}

fun encodeElevationArray(elevationsMeters: DoubleArray): ByteArray {
    val encoded = ByteArray(elevationsMeters.size * 3)
    for (index in elevationsMeters.indices) {
        val rgb = encodeElevation(elevationsMeters[index])
        val offset = index * 3
        encoded[offset] = rgb.red.toByte()
        encoded[offset + 1] = rgb.green.toByte()
        encoded[offset + 2] = rgb.blue.toByte()
    }
    return encoded
}
