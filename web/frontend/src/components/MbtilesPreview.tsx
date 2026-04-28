import 'maplibre-gl/dist/maplibre-gl.css'

import { useEffect, useRef, useState } from 'react'
import maplibregl from 'maplibre-gl'

import type { MBTilesTileset } from '../types'
import type { PreviewBase } from './MapPreview'

interface Props {
  tileset: MBTilesTileset | null
  previewBase: PreviewBase
}

function buildStyle(tileset: MBTilesTileset, previewBase: PreviewBase): maplibregl.StyleSpecification {
  const sources: maplibregl.StyleSpecification['sources'] = {
    'mbtiles-raster': {
      type: tileset.source_type,
      tiles: [tileset.tile_url_template],
      encoding: tileset.source_type === 'raster-dem' ? 'mapbox' : undefined,
      tileSize: 256,
    },
  }

  const layers: maplibregl.StyleSpecification['layers'] = [
    {
      id: 'background',
      type: 'background',
      paint: { 'background-color': '#dbeafe' },
    },
  ]

  if (previewBase === 'osm') {
    sources.osm = {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      attribution: '&copy; OpenStreetMap contributors',
    }
    layers.push({ id: 'osm', type: 'raster', source: 'osm' })
  }

  if (tileset.source_type === 'raster-dem') {
    layers.push({
      id: 'mbtiles-hillshade',
      type: 'hillshade',
      source: 'mbtiles-raster',
      paint: {
        'hillshade-shadow-color': '#1e293b',
        'hillshade-highlight-color': '#f8fafc',
        'hillshade-accent-color': '#94a3b8',
        'hillshade-exaggeration': 0.8,
      },
    })
    return {
      version: 8,
      sources,
      layers,
      terrain: { source: 'mbtiles-raster', exaggeration: 1.15 },
    }
  }

  layers.push({
    id: 'mbtiles-raster',
    type: 'raster',
    source: 'mbtiles-raster',
  })

  return {
    version: 8,
    sources,
    layers,
  }
}

export function MbtilesPreview({ tileset, previewBase }: Props) {
  const mapRef = useRef<HTMLDivElement | null>(null)
  const mapInstance = useRef<maplibregl.Map | null>(null)
  const [pitch, setPitch] = useState(55)
  const [zoom, setZoom] = useState<number | null>(null)

  useEffect(() => {
    mapInstance.current?.remove()
    mapInstance.current = null

    if (!tileset || !mapRef.current) return

    mapInstance.current = new maplibregl.Map({
      container: mapRef.current,
      style: buildStyle(tileset, previewBase),
      center: tileset.view ? [tileset.view.center_lon, tileset.view.center_lat] : [0, 0],
      zoom: tileset.view?.zoom ?? 1,
      pitch: tileset.source_type === 'raster-dem' ? pitch : 0,
      maxPitch: 85,
    })

    setZoom(mapInstance.current.getZoom())
    mapInstance.current.on('zoom', () => {
      setZoom(mapInstance.current?.getZoom() ?? null)
    })

    if (tileset.bounds) {
      mapInstance.current.fitBounds(
        [
          [tileset.bounds.west, tileset.bounds.south],
          [tileset.bounds.east, tileset.bounds.north],
        ],
        { padding: 24, duration: 0 },
      )
      if (tileset.source_type === 'raster-dem') {
        mapInstance.current.setPitch(pitch)
      }
      setZoom(mapInstance.current.getZoom())
    }

    mapInstance.current.addControl(new maplibregl.NavigationControl(), 'top-right')
    return () => {
      mapInstance.current?.remove()
      mapInstance.current = null
    }
  }, [tileset, previewBase])

  useEffect(() => {
    if (!mapInstance.current || !tileset || tileset.source_type !== 'raster-dem') return
    mapInstance.current.setPitch(pitch)
  }, [pitch, tileset])

  return (
    <div className="panel">
      <h3>MBTiles preview</h3>
      <div className="preview-toolbar">
        <label>
          Tilt: {tileset?.source_type === 'raster-dem' ? `${pitch}°` : '0°'}
          <input
            type="range"
            min="0"
            max="85"
            value={tileset?.source_type === 'raster-dem' ? pitch : 0}
            disabled={tileset?.source_type !== 'raster-dem'}
            onChange={(event) => setPitch(Number(event.target.value))}
          />
        </label>
        <div className="preview-stat">Zoom: {zoom?.toFixed(2) ?? '-'}</div>
      </div>
      <div className="map" ref={mapRef} />
    </div>
  )
}
