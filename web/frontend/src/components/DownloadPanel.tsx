import { artifactUrl } from '../api'
import type { Job } from '../types'

interface Props {
  job: Job
}

export function DownloadPanel({ job }: Props) {
  if (job.status !== 'completed') return null
  return (
    <div className="panel">
      <h3>Downloads</h3>
      <div className="download-links">
        <a href={artifactUrl(job.artifacts.terrain_mbtiles) ?? '#'}>terrain-rgb.mbtiles</a>
        <a href={artifactUrl(job.artifacts.tilejson) ?? '#'}>tiles.json</a>
        <a href={artifactUrl(job.artifacts.stylejson) ?? '#'}>style.json</a>
      </div>
    </div>
  )
}
