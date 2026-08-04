function normalizeUrl(serverUrl: string) {
  return serverUrl.replace(/\/$/, '')
}

// `/apis/mlj_1/newscrobble` is Maloja's stable, well-documented scrobble endpoint. `/test` for
// connection-check purposes is less consistently documented across Maloja versions — if this
// 404s on a real server, swap it for a harmless authenticated GET the target version does
// support (Maloja's own docs/changelog for the exact instance will confirm).
export async function testConnection(serverUrl: string, apiKey: string): Promise<void> {
  const res = await fetch(`${normalizeUrl(serverUrl)}/apis/mlj_1/test`, {
    headers: { Authorization: `Bearer ${apiKey}` },
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const json = await res.json()
  if (json.status && json.status !== 'ok') throw new Error(json.error?.description ?? 'Maloja rejected the request')
}

export async function scrobble(
  serverUrl: string, apiKey: string, artist: string, title: string,
  timestampSeconds: number, album?: string, durationSeconds?: number,
): Promise<void> {
  const body: Record<string, unknown> = {
    artists: [artist],
    title,
    time: timestampSeconds,
  }
  if (album) body.album = album
  if (durationSeconds) body.duration = Math.round(durationSeconds)

  const res = await fetch(`${normalizeUrl(serverUrl)}/apis/mlj_1/newscrobble`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}
