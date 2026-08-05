import { createRateLimiter } from '@/lib/rateLimiter'

// MusicBrainz's usage policy asks for a descriptive User-Agent identifying the app and a
// contact point (see the Android client's MusicBrainzInterceptor) — browsers refuse to let
// fetch()/XHR override that header at all (it's in the Fetch spec's forbidden-header-name
// list), so every request here goes out under the visitor's real browser UA instead. There's no
// client-side workaround for that. The rate limit below is the one part of their policy we can
// actually honor, and it's the part that matters for not getting throttled/blocked.
const withRateLimit = createRateLimiter(1100)

const BASE = 'https://musicbrainz.org/ws/2'

interface MbArtist {
  id: string
  name: string
  type?: string
  score?: number
  'life-span'?: { begin?: string; end?: string; ended?: boolean }
  tags?: { name: string; count: number }[]
}

interface MbArtistSearchResponse {
  artists?: MbArtist[]
}

async function mbFetch<T>(path: string, params: Record<string, string>): Promise<T> {
  const url = new URL(`${BASE}/${path}`)
  Object.entries({ fmt: 'json', ...params }).forEach(([k, v]) => url.searchParams.set(k, v))
  return withRateLimit(async () => {
    const res = await fetch(url.toString())
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return res.json() as Promise<T>
  })
}

export interface MusicBrainzArtistInfo {
  formedYear: number | null
  dissolvedYear: number | null
  isActive: boolean
  type: string | null
  tags: string[]
}

export async function lookupArtist(artistName: string): Promise<MusicBrainzArtistInfo | null> {
  const search = await mbFetch<MbArtistSearchResponse>('artist', {
    query: `artist:"${artistName}"`,
    limit: '1',
  })
  const artist = search.artists?.[0]
  if (!artist) return null

  const lifeSpan = artist['life-span']
  const formedYear = lifeSpan?.begin ? parseInt(lifeSpan.begin.slice(0, 4), 10) || null : null
  const dissolvedYear = lifeSpan?.end ? parseInt(lifeSpan.end.slice(0, 4), 10) || null : null
  const tags = [...(artist.tags ?? [])]
    .sort((a, b) => b.count - a.count)
    .slice(0, 6)
    .map(t => t.name)

  return {
    formedYear,
    dissolvedYear,
    isActive: dissolvedYear == null,
    type: artist.type ?? null,
    tags,
  }
}
