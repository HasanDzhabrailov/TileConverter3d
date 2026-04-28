import { useEffect, useMemo, useState } from 'react'

import { connectJob, getJob, getServerInfo, listJobs, listMbtilesTilesets } from './api'
import { ConvertForm } from './components/ConvertForm'
import { DownloadPanel } from './components/DownloadPanel'
import { JobList } from './components/JobList'
import { JobLogs } from './components/JobLogs'
import { MapPreview, type PreviewBase } from './components/MapPreview'
import { MbtilesPreview } from './components/MbtilesPreview'
import { MbtilesServerPanel } from './components/MbtilesServerPanel'
import type { Job, MBTilesTileset, ServerAddress } from './types'

function sameJson<T>(left: T, right: T): boolean {
  return JSON.stringify(left) === JSON.stringify(right)
}

export default function App() {
  const [jobs, setJobs] = useState<Job[]>([])
  const [serverAddresses, setServerAddresses] = useState<ServerAddress[]>([])
  const [tilesets, setTilesets] = useState<MBTilesTileset[]>([])
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null)
  const [selectedTilesetId, setSelectedTilesetId] = useState<string | null>(null)
  const [previewBase, setPreviewBase] = useState<PreviewBase>('osm')
  const [logs, setLogs] = useState<string[]>([])

  useEffect(() => {
    let mounted = true
    const load = async () => {
      const [nextJobs, nextTilesets, serverInfo] = await Promise.all([listJobs(), listMbtilesTilesets(), getServerInfo()])
      if (!mounted) return
      setJobs((current) => (sameJson(current, nextJobs) ? current : nextJobs))
      setTilesets((current) => (sameJson(current, nextTilesets) ? current : nextTilesets))
      setServerAddresses((current) => (sameJson(current, serverInfo.addresses) ? current : serverInfo.addresses))
      if (!selectedJobId && nextJobs[0]) setSelectedJobId(nextJobs[0].id)
      if (!selectedTilesetId && nextTilesets[0]) setSelectedTilesetId(nextTilesets[0].id)
    }
    load()
    const interval = window.setInterval(load, 5000)
    return () => {
      mounted = false
      window.clearInterval(interval)
    }
  }, [selectedJobId, selectedTilesetId])

  useEffect(() => {
    if (!selectedJobId) return
    let active = true
    setLogs([])
    getJob(selectedJobId).then((job) => {
      if (active) setLogs(job.logs ?? [])
    })
    const socket = connectJob(selectedJobId, (event) => {
      if (event.type === 'log' && event.line) {
        setLogs((current) => [...current, event.line as string])
      }
      if (event.type === 'job' && event.job) {
        setJobs((current) => [event.job!, ...current.filter((item) => item.id !== event.job!.id)])
      }
    })
    return () => {
      active = false
      socket.close()
    }
  }, [selectedJobId])

  const selectedJob = useMemo(() => jobs.find((job) => job.id === selectedJobId) ?? null, [jobs, selectedJobId])
  const selectedTileset = useMemo(() => tilesets.find((tileset) => tileset.id === selectedTilesetId) ?? null, [tilesets, selectedTilesetId])

  return (
    <div className="app-shell">
      <header>
        <h1>Terrain Converter</h1>
        <p>Upload HGT data, convert to Terrain-RGB, preview in MapLibre, and download outputs.</p>
      </header>
      <main className="layout">
        <section className="left-column">
          <MbtilesServerPanel
            serverAddresses={serverAddresses}
            tilesets={tilesets}
            selectedTilesetId={selectedTilesetId}
            onSelect={setSelectedTilesetId}
            onUploaded={(tileset) => {
              setTilesets((current) => [tileset, ...current.filter((item) => item.id !== tileset.id)])
              setSelectedTilesetId(tileset.id)
            }}
          />
          <ConvertForm onCreated={setSelectedJobId} />
          <JobList jobs={jobs} selectedJobId={selectedJobId} onSelect={setSelectedJobId} />
        </section>
        <section className="right-column">
          <div className="panel stack">
            <h3>Preview base</h3>
            <div className="grid three preview-base-grid">
              <button type="button" className={previewBase === 'osm' ? 'active-option' : ''} onClick={() => setPreviewBase('osm')}>OpenStreetMap</button>
              <button type="button" className={previewBase === 'uploaded' ? 'active-option' : ''} onClick={() => setPreviewBase('uploaded')}>Uploaded base</button>
              <button type="button" className={previewBase === 'none' ? 'active-option' : ''} onClick={() => setPreviewBase('none')}>None</button>
            </div>
          </div>
          {selectedTileset && <MbtilesPreview tileset={selectedTileset} previewBase={previewBase} />}
          {selectedJob && (
            <>
              <div className="panel">
                <h2>Job status</h2>
                <div className="status-row"><strong>{selectedJob.status}</strong>{selectedJob.error && <span className="error">{selectedJob.error}</span>}</div>
                {selectedJob.result.tile_count !== null && <div>Tiles: {selectedJob.result.tile_count}</div>}
                {previewBase === 'uploaded' && !selectedJob.has_base_mbtiles && <div className="hint">This job has no uploaded base MBTiles, so only terrain will be shown.</div>}
              </div>
              <DownloadPanel job={selectedJob} />
              <MapPreview job={selectedJob} previewBase={previewBase} />
            </>
          )}
          <JobLogs logs={logs} />
        </section>
      </main>
    </div>
  )
}
