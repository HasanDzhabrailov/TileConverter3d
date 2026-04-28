import type { ChangeEvent } from 'react'

interface Props {
  onHgtChange: (files: File[]) => void
  onBaseChange: (file: File | null) => void
}

export function UploadPanel({ onHgtChange, onBaseChange }: Props) {
  return (
    <div className="panel">
      <h3>Uploads</h3>
      <label>
        HGT files or ZIP archive
        <input
          type="file"
          multiple
          accept=".hgt,.zip"
          onChange={(event: ChangeEvent<HTMLInputElement>) => onHgtChange(Array.from(event.target.files ?? []))}
        />
      </label>
      <label>
        Optional base.mbtiles
        <input
          type="file"
          accept=".mbtiles"
          onChange={(event: ChangeEvent<HTMLInputElement>) => onBaseChange(event.target.files?.[0] ?? null)}
        />
      </label>
    </div>
  )
}
