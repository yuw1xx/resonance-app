import md5 from 'md5'

// This app's own Last.fm API credentials (distinct from Navidrome's server-side
// ND_LASTFM_APIKEY, which only powers Navidrome's own artist-info lookups). Since this is a
// pure static site with no backend, every scrobble call has to be signed client-side — the
// secret below ships inside the public JS bundle and is trivially extractable. That's a
// known, accepted tradeoff (see the plan this shipped under): it can't expose a user's
// Last.fm password, and each scrobble is additionally gated by a per-user session key from
// their own login, so the worst case is someone else burning this app's shared rate limit.
const API_KEY = 'e0b23d4574734b27c23b944387edf0f8'
const API_SECRET = 'd767aa481e030df4a32508b4d8a471c3'

const BASE = 'https://ws.audioscrobbler.com/2.0/'

// Per Last.fm's signing spec: sign every param except `format`/`callback`, sorted
// alphabetically by name, concatenated as `namevalue` pairs with no separators, secret
// appended, then md5'd — `format` must be added to the request only *after* signing.
function sign(params: Record<string, string>): string {
  const sorted = Object.keys(params).sort()
  const concatenated = sorted.map(k => `${k}${params[k]}`).join('')
  return md5(concatenated + API_SECRET)
}

async function call(
  method: string,
  params: Record<string, string>,
  opts: { signed?: boolean; http?: 'GET' | 'POST' } = {},
): Promise<any> {
  const base: Record<string, string> = { method, api_key: API_KEY, ...params }
  const withSig = opts.signed ? { ...base, api_sig: sign(base) } : base
  const full = { ...withSig, format: 'json' }

  if ((opts.http ?? 'GET') === 'GET') {
    const url = new URL(BASE)
    Object.entries(full).forEach(([k, v]) => url.searchParams.set(k, v))
    const res = await fetch(url.toString())
    const json = await res.json()
    if (json.error) throw new Error(json.message ?? `Last.fm error ${json.error}`)
    return json
  }

  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(full),
  })
  const json = await res.json()
  if (json.error) throw new Error(json.message ?? `Last.fm error ${json.error}`)
  return json
}

export function buildAuthUrl(token: string, callbackUrl: string): string {
  const url = new URL('https://www.last.fm/api/auth/')
  url.searchParams.set('api_key', API_KEY)
  url.searchParams.set('token', token)
  url.searchParams.set('cb', callbackUrl)
  return url.toString()
}

export async function getToken(): Promise<string> {
  const json = await call('auth.getToken', {}, { signed: true })
  return json.token as string
}

export async function getSession(token: string): Promise<{ key: string; name: string }> {
  const json = await call('auth.getSession', { token }, { signed: true })
  return { key: json.session.key as string, name: json.session.name as string }
}

export async function updateNowPlaying(
  sessionKey: string, artist: string, track: string, album?: string, durationSeconds?: number,
): Promise<void> {
  const params: Record<string, string> = { artist, track, sk: sessionKey }
  if (album) params.album = album
  if (durationSeconds) params.duration = String(Math.round(durationSeconds))
  await call('track.updateNowPlaying', params, { signed: true, http: 'POST' })
}

export async function scrobble(
  sessionKey: string, artist: string, track: string, timestampSeconds: number,
  album?: string, durationSeconds?: number,
): Promise<void> {
  const params: Record<string, string> = {
    artist, track, sk: sessionKey, timestamp: String(timestampSeconds),
  }
  if (album) params.album = album
  if (durationSeconds) params.duration = String(Math.round(durationSeconds))
  await call('track.scrobble', params, { signed: true, http: 'POST' })
}
