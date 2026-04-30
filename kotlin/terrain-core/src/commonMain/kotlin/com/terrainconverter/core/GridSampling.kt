package com.terrainconverter.core

data class GridSampleResult(
    val values: DoubleArray,
    val valid: BooleanArray,
    val width: Int,
    val height: Int,
)

fun HgtTile.sampleBilinearGrid(lon: DoubleArray, lat: DoubleArray): Pair<DoubleArray, BooleanArray> {
    require(lon.size == lat.size) { "lon and lat grids must have the same shape" }
    val values = DoubleArray(lon.size)
    val valid = BooleanArray(lon.size)
    for (index in lon.indices) {
        val value = sampleBilinear(lon[index], lat[index])
        if (value != null) {
            values[index] = value
            valid[index] = true
        }
    }
    return Pair(values, valid)
}
