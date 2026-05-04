package com.terrainconverter.web.ui

import org.w3c.dom.HTMLElement

@JsModule("maplibre-gl")
@JsNonModule
private external object MapLibreGlModule

external interface MapLibreMap {
    fun remove()
    fun getZoom(): Double
    fun resize()
    fun setPitch(pitch: Double)
    fun fitBounds(bounds: Array<Array<Double>>, options: dynamic = definedExternally)
    fun addControl(control: dynamic, position: String = definedExternally)
    fun on(type: String, listener: (dynamic) -> Unit)
}

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
    val mapConstructor = mapLibreExport("Map")
    return js("new mapConstructor(options)").unsafeCast<MapLibreMap>()
}

fun createNavigationControl(): dynamic {
    val navigationControlConstructor = mapLibreExport("NavigationControl")
    return js("new navigationControlConstructor()")
}

private fun mapLibreExport(name: String): dynamic {
    val module = MapLibreGlModule.asDynamic()
    return module[name] ?: module.default?.unsafeCast<dynamic>()?.get(name) ?: module.default ?: module
}
