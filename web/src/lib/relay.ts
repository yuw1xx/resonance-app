export interface RelaySongEntry {
  token: string
  title: string
  artist: string
  mime: string
  ext: string
}

const MIME_BY_EXT: Record<string, string> = {
  mp3: 'audio/mpeg', flac: 'audio/flac', ogg: 'audio/ogg', opus: 'audio/opus',
  m4a: 'audio/mp4', aac: 'audio/aac', wav: 'audio/wav', wma: 'audio/x-ms-wma',
}

export function mimeForSuffix(suffix?: string): string {
  return MIME_BY_EXT[(suffix ?? '').toLowerCase()] ?? 'application/octet-stream'
}

function normalizeUrl(serverUrl: string) {
  return serverUrl.replace(/\/$/, '')
}

export async function uploadFile(
  serverUrl: string, uploadToken: string, blob: Blob, ttlHours?: number,
): Promise<string> {
  const url = new URL(`${normalizeUrl(serverUrl)}/upload`)
  if (ttlHours) url.searchParams.set('ttlHours', String(ttlHours))
  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: { Authorization: `Bearer ${uploadToken}` },
    body: blob,
  })
  if (!res.ok) throw new Error(`Upload failed (HTTP ${res.status})`)
  const json = await res.json()
  return json.token as string
}

export async function createManifest(
  serverUrl: string, uploadToken: string, songs: RelaySongEntry[], ttlHours?: number,
): Promise<string> {
  const res = await fetch(`${normalizeUrl(serverUrl)}/manifest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${uploadToken}` },
    body: JSON.stringify({ ttlHours, songs }),
  })
  if (!res.ok) throw new Error(`Manifest creation failed (HTTP ${res.status})`)
  const json = await res.json()
  return json.token as string
}

export function fileUrl(serverUrl: string, token: string): string {
  return `${normalizeUrl(serverUrl)}/f/${token}`
}

export function manifestUrl(serverUrl: string, token: string): string {
  return `${normalizeUrl(serverUrl)}/manifest/${token}`
}

export async function getManifest(serverUrl: string, token: string): Promise<RelaySongEntry[]> {
  const res = await fetch(manifestUrl(serverUrl, token))
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function downloadFile(serverUrl: string, token: string): Promise<Blob> {
  const res = await fetch(fileUrl(serverUrl, token))
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.blob()
}
