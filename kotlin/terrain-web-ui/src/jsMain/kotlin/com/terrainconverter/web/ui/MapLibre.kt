package com.terrainconverter.web.ui

import org.w3c.dom.HTMLElement
import kotlin.js.Promise

@JsModule("maplibre-gl")
@JsNonModule
private external object MapLibreGlModule

external interface MapLibreMap {
    fun remove()
    fun getZoom(): Double
    fun getPitch(): Double
    fun resize()
    fun setPitch(pitch: Double)
    fun fitBounds(bounds: Array<Array<Double>>, options: dynamic = definedExternally)
    fun addControl(control: dynamic, position: String = definedExternally)
    fun on(type: String, listener: (dynamic) -> Unit)
}

// Service Worker Cache API для управления кэшем тайлов
external interface CacheStorage {
    fun keys(): Promise<Array<dynamic>>
    fun delete(cacheName: String): Promise<Boolean>
    fun open(cacheName: String): Promise<Cache>
}

external interface Cache {
    fun keys(): Promise<Array<dynamic>>
    fun delete(request: dynamic, options: dynamic = definedExternally): Promise<Boolean>
}

// Отправка сообщения Service Worker для очистки кэша
fun clearTileCache(): Promise<Boolean> {
    return js("""
        new Promise(function(resolve, reject) {
            if (!navigator.serviceWorker || !navigator.serviceWorker.controller) {
                resolve(false);
                return;
            }
            var channel = new MessageChannel();
            channel.port1.onmessage = function(event) {
                resolve(event.data && event.data.success);
            };
            navigator.serviceWorker.controller.postMessage('clear-tile-cache', [channel.port2]);
        })
    """).unsafeCast<Promise<Boolean>>()
}

// Получение статистики кэша
fun getTileCacheStats(): Promise<TileCacheStats> {
    return js("""
        new Promise(function(resolve, reject) {
            if (!navigator.serviceWorker || !navigator.serviceWorker.controller) {
                resolve({ tileCount: 0, totalSize: 0 });
                return;
            }
            var channel = new MessageChannel();
            channel.port1.onmessage = function(event) {
                resolve(event.data || { tileCount: 0, totalSize: 0 });
            };
            navigator.serviceWorker.controller.postMessage('get-cache-stats', [channel.port2]);
        })
    """).unsafeCast<Promise<TileCacheStats>>()
}

data class TileCacheStats(
    val tileCount: Int,
    val totalSize: Int,
)

fun createMapLibreMap(
    container: HTMLElement,
    style: dynamic,
    center: Array<Double>,
    zoom: Double,
    pitch: Double,
    maxPitch: Double = 85.0,
    maxTileCacheSize: Int? = null,
    cacheSize: Int? = null,
): MapLibreMap {
    val options = js("({})")
    options.container = container
    options.style = style
    options.center = center
    options.zoom = zoom
    options.pitch = pitch
    options.maxPitch = maxPitch
    // Performance optimizations
    options.antialias = false
    options.preserveDrawingBuffer = false
    // Limit tile cache to prevent memory bloat (default is unlimited)
    options.maxTileCacheSize = maxTileCacheSize ?: 128
    options.cacheSize = cacheSize ?: 128
    // Reduce tile requests for slow connections
    options.transformRequest = { url: String, resourceType: String ->
        val req = js("({})")
        req.url = url
        req.credentials = "same-origin"
        req
    }
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
