export interface LrcLibLine {
  timeMs: number
  text: string
}

interface LrcLibResponse {
  syncedLyrics?: string
  plainLyrics?: string
}

export async function fetchLrcLibLyrics(
  title: string,
  artist: string,
  album: string,
  durationSeconds: number,
): Promise<LrcLibLine[] | null> {
  const params = new URLSearchParams({
    track_name: title,
    artist_name: artist,
    album_name: album,
    duration: String(Math.round(durationSeconds)),
  })
  const res = await fetch(`https://lrclib.net/api/get?${params}`)
  if (!res.ok) return null
  const data: LrcLibResponse = await res.json()
  if (!data.syncedLyrics) return null
  return parseLrc(data.syncedLyrics)
}

function parseLrc(lrc: string): LrcLibLine[] {
  const lines: LrcLibLine[] = []
  for (const line of lrc.split('\n')) {
    const match = line.match(/^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$/)
    if (!match) continue
    const minutes = parseInt(match[1], 10)
    const seconds = parseInt(match[2], 10)
    const centiseconds = parseInt(match[3].padEnd(3, '0'), 10)
    const timeMs = (minutes * 60 + seconds) * 1000 + centiseconds
    const text = match[4].trim()
    lines.push({ timeMs, text })
  }
  return lines
}
