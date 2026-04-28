export type JobStatus = 'pending' | 'running' | 'completed' | 'failed'

export interface BBox {
  west: number
  south: number
  east: number
  north: number
}

export interface MapView {
  center_lon: number
  center_lat: number
  zoom: number
}

export interface JobOptions {
  bbox_mode: 'auto' | 'manual'
  bbox: BBox | null
  minzoom: number
  maxzoom: number
  tile_size: number
  scheme: 'xyz' | 'tms'
  encoding: 'mapbox'
}

export interface JobArtifacts {
  terrain_mbtiles: string | null
  tilejson: string | null
  stylejson: string | null
}

export interface JobResult {
  bounds: BBox | null
  tile_count: number | null
}

export interface Job {
  id: string
  status: JobStatus
  created_at: string
  updated_at: string
  options: JobOptions
  has_base_mbtiles: boolean
  artifacts: JobArtifacts
  result: JobResult
  error: string | null
  logs?: string[]
}

export interface MBTilesTileset {
  id: string
  filename: string
  created_at: string
  tile_url_template: string
  public_tile_url_template: string
  tilejson_url: string | null
  public_tilejson_url: string | null
  style_url: string | null
  public_style_url: string | null
  mobile_style_url: string | null
  public_mobile_style_url: string | null
  name: string | null
  attribution: string | null
  source_type: 'raster' | 'raster-dem'
  tile_format: string
  minzoom: number | null
  maxzoom: number | null
  bounds: BBox | null
  view: MapView | null
}

export interface ServerAddress {
  id: string
  label: string
  host: string
  base_url: string
  description: string
}

export interface ServerInfo {
  addresses: ServerAddress[]
}

export interface WsEvent {
  type: 'job' | 'log'
  job?: Job
  line?: string
}
