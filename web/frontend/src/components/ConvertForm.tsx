import type { FormEvent } from 'react'
import { useState } from 'react'

import { createJob } from '../api'
import { UploadPanel } from './UploadPanel'

interface Props {
  onCreated: (jobId: string) => void
}

export function ConvertForm({ onCreated }: Props) {
  const [hgtFiles, setHgtFiles] = useState<File[]>([])
  const [baseFile, setBaseFile] = useState<File | null>(null)
  const [bboxMode, setBboxMode] = useState<'auto' | 'manual'>('auto')
  const [west, setWest] = useState('')
  const [south, setSouth] = useState('')
  const [east, setEast] = useState('')
  const [north, setNorth] = useState('')
  const [minzoom, setMinzoom] = useState(8)
  const [maxzoom, setMaxzoom] = useState(12)
  const [tileSize, setTileSize] = useState(256)
  const [scheme, setScheme] = useState<'xyz' | 'tms'>('xyz')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (hgtFiles.length === 0) {
      setError('Upload at least one HGT file or ZIP archive.')
      return
    }
    const formData = new FormData()
    hgtFiles.forEach((file) => formData.append('hgt_files', file))
    if (baseFile) formData.append('base_mbtiles', baseFile)
    formData.append('bbox_mode', bboxMode)
    formData.append('minzoom', String(minzoom))
    formData.append('maxzoom', String(maxzoom))
    formData.append('tile_size', String(tileSize))
    formData.append('scheme', scheme)
    formData.append('encoding', 'mapbox')
    if (bboxMode === 'manual') {
      formData.append('west', west)
      formData.append('south', south)
      formData.append('east', east)
      formData.append('north', north)
    }
    try {
      setSubmitting(true)
      const job = await createJob(formData)
      onCreated(job.id)
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Failed to create job')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="panel stack" onSubmit={onSubmit}>
      <h2>New conversion</h2>
      <UploadPanel onHgtChange={setHgtFiles} onBaseChange={setBaseFile} />
      <div className="grid two">
        <label>
          BBox mode
          <select value={bboxMode} onChange={(e) => setBboxMode(e.target.value as 'auto' | 'manual')}>
            <option value="auto">auto</option>
            <option value="manual">manual</option>
          </select>
        </label>
        <label>
          Scheme
          <select value={scheme} onChange={(e) => setScheme(e.target.value as 'xyz' | 'tms')}>
            <option value="xyz">xyz</option>
            <option value="tms">tms</option>
          </select>
        </label>
      </div>
      {bboxMode === 'manual' && (
        <div className="grid four">
          <label>West<input value={west} onChange={(e) => setWest(e.target.value)} /></label>
          <label>South<input value={south} onChange={(e) => setSouth(e.target.value)} /></label>
          <label>East<input value={east} onChange={(e) => setEast(e.target.value)} /></label>
          <label>North<input value={north} onChange={(e) => setNorth(e.target.value)} /></label>
        </div>
      )}
      <div className="grid four">
        <label>Min zoom<input type="number" value={minzoom} onChange={(e) => setMinzoom(Number(e.target.value))} /></label>
        <label>Max zoom<input type="number" value={maxzoom} onChange={(e) => setMaxzoom(Number(e.target.value))} /></label>
        <label>Tile size<input type="number" value={tileSize} onChange={(e) => setTileSize(Number(e.target.value))} /></label>
        <label>
          Encoding
          <input value="mapbox" disabled />
        </label>
      </div>
      {error && <div className="error">{error}</div>}
      <button type="submit" disabled={submitting}>{submitting ? 'Starting…' : 'Start conversion'}</button>
    </form>
  )
}
