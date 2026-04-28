import 'maplibre-gl/dist/maplibre-gl.css'

import { useEffect, useRef, useState } from 'react'
import maplibregl from 'maplibre-gl'

import type { Job } from '../types'

export type PreviewBase = 'osm' | 'uploaded' | 'none'

interface Props {
  job: Job | null
  previewBase: PreviewBase
}

function buildStyle(job: Job, previewBase: PreviewBase): maplibregl.StyleSpecification {
  const sources: maplibregl.StyleSpecification['sources'] = {
    terrain: {
      type: 'raster-dem',
      tiles: [`/api/jobs/${job.id}/terrain/{z}/{x}/{y}.png`],
      encoding: job.options.encoding,
      tileSize: job.options.tile_size,
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

  if (previewBase === 'uploaded' && job.has_base_mbtiles) {
    sources['uploaded-base'] = {
      type: 'raster',
      tiles: [`/api/jobs/${job.id}/base/{z}/{x}/{y}`],
      tileSize: 256,
    }
    layers.push({ id: 'uploaded-base', type: 'raster', source: 'uploaded-base' })
  }

  layers.push({
    id: 'terrain-hillshade',
    type: 'hillshade',
    source: 'terrain',
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
    terrain: { source: 'terrain', exaggeration: 1.15 },
  }
}

export function MapPreview({ job, previewBase }: Props) {
  const mapRef = useRef<HTMLDivElement | null>(null)
  const mapInstance = useRef<maplibregl.Map | null>(null)
  const [pitch, setPitch] = useState(55)
  const [zoom, setZoom] = useState<number | null>(null)

  useEffect(() => {
    mapInstance.current?.remove()
    mapInstance.current = null

    if (!job || job.status !== 'completed' || !mapRef.current) return

    mapInstance.current = new maplibregl.Map({
      container: mapRef.current,
      style: buildStyle(job, previewBase),
      center: job.result.bounds
        ? [
            (job.result.bounds.west + job.result.bounds.east) / 2,
            (job.result.bounds.south + job.result.bounds.north) / 2,
          ]
        : [0, 0],
      zoom: Math.max(job.options.minzoom, 8),
      pitch,
      maxPitch: 85,
    })

    setZoom(mapInstance.current.getZoom())
    mapInstance.current.on('zoom', () => {
      setZoom(mapInstance.current?.getZoom() ?? null)
    })

    if (job.result.bounds) {
      mapInstance.current.fitBounds(
        [
          [job.result.bounds.west, job.result.bounds.south],
          [job.result.bounds.east, job.result.bounds.north],
        ],
        { padding: 24, duration: 0 },
      )
      mapInstance.current.setPitch(pitch)
      setZoom(mapInstance.current.getZoom())
    }

    mapInstance.current.addControl(new maplibregl.NavigationControl(), 'top-right')
    return () => {
      mapInstance.current?.remove()
      mapInstance.current = null
    }
  }, [job, previewBase])

  useEffect(() => {
    if (!mapInstance.current) return
    mapInstance.current.setPitch(pitch)
  }, [pitch])

  return (
    <div className="panel">
      <h3>Preview</h3>
      <div className="preview-toolbar">
        <label>
          Tilt: {pitch}°
          <input type="range" min="0" max="85" value={pitch} onChange={(event) => setPitch(Number(event.target.value))} />
        </label>
        <div className="preview-stat">Zoom: {zoom?.toFixed(2) ?? '-'}</div>
      </div>
      <div className="map" ref={mapRef} />
    </div>
  )
}
