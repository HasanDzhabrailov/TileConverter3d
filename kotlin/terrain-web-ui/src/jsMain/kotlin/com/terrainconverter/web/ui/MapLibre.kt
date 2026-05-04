package com.terrainconverter.web.ui

import org.w3c.dom.HTMLElement

@JsModule("maplibre-gl")
@JsNonModule
@JsName("Map")
external class MapLibreMap(options: dynamic) {
    fun remove()
    fun getZoom(): Double
    fun setPitch(pitch: Double)
    fun fitBounds(bounds: Array<Array<Double>>, options: dynamic = definedExternally)
    fun addControl(control: dynamic, position: String = definedExternally)
    fun on(type: String, listener: () -> Unit)
}

@JsModule("maplibre-gl")
@JsNonModule
@JsName("NavigationControl")
external class MapLibreNavigationControl()

fun createMapLibreMap(
    container: HTMLElement,
    style: dynamic,
    center: Array<Double>,
    zoom: Double,
    pitch: Double,
    maxPitch: Double = 85.0,
): MapLibreMap {
    val options = js("({})")
    options.container = container
    options.style = style
    options.center = center
    options.zoom = zoom
    options.pitch = pitch
    options.maxPitch = maxPitch
    return MapLibreMap(options)
}

fun createNavigationControl(): dynamic = MapLibreNavigationControl()
