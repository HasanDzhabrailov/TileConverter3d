interface Props {
  logs: string[]
}

export function JobLogs({ logs }: Props) {
  return (
    <div className="panel">
      <h3>Live logs</h3>
      <pre className="logs">{logs.join('\n')}</pre>
    </div>
  )
}
