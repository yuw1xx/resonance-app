export interface DiscogsMember {
  id: number
  name: string
  active?: boolean
}

export interface DiscogsArtist {
  id: number
  name: string
  profile?: string
  members?: DiscogsMember[]
  urls?: string[]
}

interface DiscogsSearchResult {
  results?: { id: number; title: string; type: string }[]
}

function authHeader(token: string): Record<string, string> {
  return { Authorization: `Discogs token=${token}` }
}

export async function searchArtist(name: string, token: string): Promise<{ id: number; name: string } | null> {
  const url = new URL('https://api.discogs.com/database/search')
  url.searchParams.set('q', name)
  url.searchParams.set('type', 'artist')
  const res = await fetch(url.toString(), { headers: authHeader(token) })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const json: DiscogsSearchResult = await res.json()
  const best = json.results?.find(r => r.type === 'artist')
  return best ? { id: best.id, name: best.title } : null
}

export async function getArtist(id: number, token: string): Promise<DiscogsArtist> {
  const res = await fetch(`https://api.discogs.com/artists/${id}`, { headers: authHeader(token) })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

/** Convenience: search by name then fetch the top match's full profile in one call. */
export async function lookupArtist(name: string, token: string): Promise<DiscogsArtist | null> {
  const match = await searchArtist(name, token)
  if (!match) return null
  return getArtist(match.id, token)
}
