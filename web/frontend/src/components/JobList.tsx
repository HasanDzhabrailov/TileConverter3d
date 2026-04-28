import type { Job } from '../types'

interface Props {
  jobs: Job[]
  selectedJobId: string | null
  onSelect: (jobId: string) => void
}

export function JobList({ jobs, selectedJobId, onSelect }: Props) {
  return (
    <div className="panel">
      <h2>Jobs</h2>
      <div className="job-list">
        {jobs.map((job) => (
          <button
            key={job.id}
            className={`job-card ${selectedJobId === job.id ? 'selected' : ''}`}
            onClick={() => onSelect(job.id)}
          >
            <strong>{job.id.slice(0, 8)}</strong>
            <span>{job.status}</span>
            <small>{new Date(job.created_at).toLocaleString()}</small>
          </button>
        ))}
        {jobs.length === 0 && <div>No jobs yet.</div>}
      </div>
    </div>
  )
}
