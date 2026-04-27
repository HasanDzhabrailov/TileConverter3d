package org.maplibre.demo.terrain

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap

object MapLibreTerrainUsage {
    fun attachTerrain(mapLibreMap: MapLibreMap) {
        mapLibreMap.uiSettings.isTiltGesturesEnabled = true
        mapLibreMap.setStyle(TerrainDemoStyle.STYLE_JSON) { style ->
            check(style.getSource(TerrainDemoStyle.TERRAIN_SOURCE_ID) != null) {
                "Generated style must include the terrain DEM source"
            }
            mapLibreMap.cameraPosition = CameraPosition.Builder()
                .tilt(60.0)
                .zoom(12.0)
                .build()
        }
    }
}
