import type { Job, MBTilesTileset, ServerInfo, WsEvent } from './types'

export async function listJobs(): Promise<Job[]> {
  const response = await fetch('/api/jobs')
  if (!response.ok) throw new Error('Failed to load jobs')
  return response.json()
}

export async function getJob(jobId: string): Promise<Job> {
  const response = await fetch(`/api/jobs/${jobId}`)
  if (!response.ok) throw new Error('Failed to load job')
  return response.json()
}

export async function createJob(formData: FormData): Promise<Job> {
  const response = await fetch('/api/jobs', { method: 'POST', body: formData })
  if (!response.ok) {
    const body = await response.text()
    throw new Error(body || 'Failed to create job')
  }
  return response.json()
}

export async function listMbtilesTilesets(): Promise<MBTilesTileset[]> {
  const response = await fetch('/api/mbtiles')
  if (!response.ok) throw new Error('Failed to load MBTiles tilesets')
  return response.json()
}

export async function uploadMbtilesTileset(formData: FormData): Promise<MBTilesTileset> {
  const response = await fetch('/api/mbtiles', { method: 'POST', body: formData })
  if (!response.ok) {
    const body = await response.text()
    throw new Error(body || 'Failed to upload MBTiles')
  }
  return response.json()
}

export async function getServerInfo(): Promise<ServerInfo> {
  const response = await fetch('/api/server-info')
  if (!response.ok) throw new Error('Failed to load server info')
  return response.json()
}

export function connectJob(jobId: string, onEvent: (event: WsEvent) => void): WebSocket {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const socket = new WebSocket(`${protocol}://${window.location.host}/ws/jobs/${jobId}`)
  socket.onmessage = (message) => onEvent(JSON.parse(message.data) as WsEvent)
  return socket
}

export function artifactUrl(path: string | null): string | null {
  return path
}

export function styleUrl(jobId: string): string {
  return `/api/jobs/${jobId}/style`
}

export function absoluteUrl(path: string): string {
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  const host = window.location.hostname
  const port = window.location.port ? `:${window.location.port}` : ''
  return `${window.location.protocol}//${host}${port}${path}`
}
