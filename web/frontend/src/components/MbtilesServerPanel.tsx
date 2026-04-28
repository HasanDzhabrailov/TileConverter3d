import type { ChangeEvent, FormEvent } from 'react'
import { useState } from 'react'

import { absoluteUrl, uploadMbtilesTileset } from '../api'
import type { MBTilesTileset, ServerAddress } from '../types'

interface Props {
  serverAddresses: ServerAddress[]
  tilesets: MBTilesTileset[]
  selectedTilesetId: string | null
  onSelect: (tilesetId: string) => void
  onUploaded: (tileset: MBTilesTileset) => void
}

function tileUrlForAddress(address: ServerAddress, tileset: MBTilesTileset): string {
  return absoluteUrl(`${address.base_url}${tileset.tile_url_template}`)
}

function tilejsonUrlForAddress(address: ServerAddress, tileset: MBTilesTileset): string | null {
  return tileset.tilejson_url ? absoluteUrl(`${address.base_url}${tileset.tilejson_url}`) : null
}

function styleUrlForAddress(address: ServerAddress, tileset: MBTilesTileset): string | null {
  return tileset.style_url ? absoluteUrl(`${address.base_url}${tileset.style_url}`) : null
}

function mobileStyleUrlForAddress(address: ServerAddress, tileset: MBTilesTileset): string | null {
  return tileset.mobile_style_url ? absoluteUrl(`${address.base_url}${tileset.mobile_style_url}`) : null
}

interface AddressLinkProps {
  label: string
  value: string
  onCopy: (event: React.MouseEvent<HTMLButtonElement>, value: string) => void
}

function AddressLink({ label, value, onCopy }: AddressLinkProps) {
  return (
    <div className="tile-server-link-row">
      <code>{label}: {value}</code>
      <button type="button" className="icon-button" onClick={(event) => onCopy(event, value)} aria-label={`Copy ${label}`} title={`Copy ${label}`}>Copy</button>
    </div>
  )
}

export function MbtilesServerPanel({ serverAddresses, tilesets, selectedTilesetId, onSelect, onUploaded }: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [sourceType, setSourceType] = useState<'auto' | 'raster' | 'raster-dem'>('auto')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function copyTileUrl(event: React.MouseEvent<HTMLButtonElement>, tileUrl: string) {
    event.stopPropagation()
    try {
      await navigator.clipboard.writeText(tileUrl)
    } catch {
      setError('Failed to copy tile URL')
    }
  }

  function onCardKeyDown(event: React.KeyboardEvent<HTMLDivElement>, tilesetId: string) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    onSelect(tilesetId)
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!file) {
      setError('Choose an MBTiles file first.')
      return
    }
    const formData = new FormData()
    formData.append('mbtiles', file)
    formData.append('source_type', sourceType)
    try {
      setSubmitting(true)
      const tileset = await uploadMbtilesTileset(formData)
      onUploaded(tileset)
      setFile(null)
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Failed to upload MBTiles')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="panel stack">
      <h2>MBTiles server</h2>
      <form className="stack" onSubmit={onSubmit}>
        <label>
          MBTiles file
          <input
            type="file"
            accept=".mbtiles"
            onChange={(event: ChangeEvent<HTMLInputElement>) => setFile(event.target.files?.[0] ?? null)}
          />
        </label>
        <label>
          MBTiles type
          <select value={sourceType} onChange={(event) => setSourceType(event.target.value as 'auto' | 'raster' | 'raster-dem')}>
            <option value="auto">Auto detect</option>
            <option value="raster">Raster map</option>
            <option value="raster-dem">Terrain DEM</option>
          </select>
        </label>
        {error && <div className="error">{error}</div>}
        <button type="submit" disabled={submitting}>{submitting ? 'Uploading…' : 'Start tile server'}</button>
      </form>
      <div className="stack">
        {tilesets.map((tileset) => (
          (() => {
            const primaryAddress = serverAddresses.find((address) => address.id === 'mobile') ?? serverAddresses[0]
            const tileUrl = primaryAddress ? tileUrlForAddress(primaryAddress, tileset) : absoluteUrl(tileset.public_tile_url_template)
            const primaryTilejsonUrl = primaryAddress ? tilejsonUrlForAddress(primaryAddress, tileset) : null
            const primaryStyleUrl = primaryAddress ? styleUrlForAddress(primaryAddress, tileset) : null
            const primaryMobileStyleUrl = primaryAddress ? mobileStyleUrlForAddress(primaryAddress, tileset) : null
            return (
          <div
            key={tileset.id}
            className={`tile-server-card ${selectedTilesetId === tileset.id ? 'selected' : ''}`}
            role="button"
            tabIndex={0}
            onClick={() => onSelect(tileset.id)}
            onKeyDown={(event) => onCardKeyDown(event, tileset.id)}
          >
            <div className="tile-server-header">
              <strong>{tileset.name || tileset.filename}</strong>
              <button type="button" className="icon-button" onClick={(event) => copyTileUrl(event, tileUrl)} aria-label="Copy tile server URL" title="Copy tile server URL">Copy</button>
            </div>
            <span>{tileset.source_type}</span>
            {tileset.attribution && <span>{tileset.attribution}</span>}
            <span>Zoom: {tileset.minzoom ?? '-'} to {tileset.maxzoom ?? '-'}</span>
            <code>{tileUrl}</code>
            <div className="tile-server-quick-links">
              {primaryMobileStyleUrl && <button type="button" className="icon-button" onClick={(event) => copyTileUrl(event, primaryMobileStyleUrl)}>Copy Mobile Style</button>}
              {primaryStyleUrl && <button type="button" className="icon-button" onClick={(event) => copyTileUrl(event, primaryStyleUrl)}>Copy Style</button>}
              {primaryTilejsonUrl && <button type="button" className="icon-button" onClick={(event) => copyTileUrl(event, primaryTilejsonUrl)}>Copy TileJSON</button>}
            </div>
            <div className="tile-server-addresses">
              {serverAddresses.map((address) => {
                const addressTileUrl = tileUrlForAddress(address, tileset)
                const addressTilejsonUrl = tilejsonUrlForAddress(address, tileset)
                const addressStyleUrl = styleUrlForAddress(address, tileset)
                const addressMobileStyleUrl = mobileStyleUrlForAddress(address, tileset)
                return (
                  <div key={address.id} className="tile-server-address-row">
                    <div className="tile-server-address-text">
                      <strong>{address.label}</strong>
                      <span>{address.description}</span>
                      {addressMobileStyleUrl && <AddressLink label="Mobile Style" value={addressMobileStyleUrl} onCopy={copyTileUrl} />}
                      {addressTilejsonUrl && <AddressLink label="TileJSON" value={addressTilejsonUrl} onCopy={copyTileUrl} />}
                      {addressStyleUrl && <AddressLink label="Style" value={addressStyleUrl} onCopy={copyTileUrl} />}
                      <AddressLink label="Tiles" value={addressTileUrl} onCopy={copyTileUrl} />
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
            )
          })()
        ))}
        {tilesets.length === 0 && <div>No MBTiles servers yet.</div>}
      </div>
    </div>
  )
}
