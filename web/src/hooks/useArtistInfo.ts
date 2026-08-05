import { useQuery } from '@tanstack/react-query'
import { lookupArtist as lookupMusicBrainzArtist } from '@/api/musicbrainz'
import { getArtistTags } from '@/lib/lastfm'

const CACHE_KEY = 'resonance-artist-info-cache'
const CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000

export interface ArtistInfo {
  formedYear: number | null
  dissolvedYear: number | null
  isActive: boolean
  type: string | null
  genres: string[]
  sourceAttribution: string[]
}

interface CacheEntry {
  fetchedAt: number
  data: ArtistInfo
}

function readCache(): Record<string, CacheEntry> {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function writeCacheEntry(artistName: string, data: ArtistInfo) {
  try {
    const cache = readCache()
    cache[artistName] = { fetchedAt: Date.now(), data }
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch {
    // Storage quota exceeded/unavailable — the info still renders this session, just refetches next time.
  }
}

async function fetchArtistInfo(artistName: string): Promise<ArtistInfo> {
  const cached = readCache()[artistName]
  if (cached && Date.now() - cached.fetchedAt < CACHE_TTL_MS) return cached.data

  const [mb, lastFmTags] = await Promise.all([
    lookupMusicBrainzArtist(artistName).catch(() => null),
    getArtistTags(artistName).catch(() => []),
  ])

  const info: ArtistInfo = {
    formedYear: mb?.formedYear ?? null,
    dissolvedYear: mb?.dissolvedYear ?? null,
    isActive: mb ? mb.isActive : true,
    type: mb?.type ?? null,
    genres: [...(mb?.tags ?? []), ...lastFmTags].filter((v, i, arr) => arr.indexOf(v) === i).slice(0, 6),
    sourceAttribution: [
      ...(mb ? ['MusicBrainz'] : []),
      ...(lastFmTags.length ? ['Last.fm'] : []),
    ],
  }
  writeCacheEntry(artistName, info)
  return info
}

/** Combines MusicBrainz (formed/dissolved year, type, tags) and Last.fm (genre tags) into one
 * ArtistInfo — mirrors the Android app's ArtistInfoRepository, minus Discogs (already handled
 * separately in ArtistDetailPage since it depends on a per-user token) and minus bio (already
 * covered by Navidrome's own getArtistInfo2, which this deliberately doesn't duplicate). */
export function useArtistInfo(artistName: string | undefined) {
  return useQuery({
    queryKey: ['musicBrainzArtistInfo', artistName],
    queryFn: () => fetchArtistInfo(artistName!),
    enabled: !!artistName,
    staleTime: Infinity,
  })
}
