import md5 from 'md5'

export interface SubsonicSong {
  id: string
  title: string
  artist?: string
  artistId?: string
  albumArtist?: string
  album?: string
  albumId?: string
  coverArt?: string
  duration?: number
  bitRate?: number
  year?: number
  genre?: string
  track?: number
  discNumber?: number
  suffix?: string
  playCount?: number
  starred?: string
}

export interface SubsonicAlbum {
  id: string
  name: string
  artist?: string
  artistId?: string
  coverArt?: string
  songCount?: number
  duration?: number
  year?: number
  playCount?: number
  song?: SubsonicSong[]
}

export interface SubsonicArtistSummary {
  id: string
  name: string
  albumCount?: number
  coverArt?: string
}

export interface SubsonicArtist {
  id: string
  name: string
  coverArt?: string
  albumCount?: number
  album?: SubsonicAlbum[]
}

export interface SubsonicPlaylistSummary {
  id: string
  name: string
  songCount?: number
  duration?: number
  coverArt?: string
}

export interface SubsonicPlaylistWithSongs {
  id: string
  name: string
  entry?: SubsonicSong[]
}

export interface SubsonicSearchResult {
  song?: SubsonicSong[]
  album?: SubsonicAlbum[]
  artist?: SubsonicArtistSummary[]
}

export interface SubsonicStarred2 {
  song?: SubsonicSong[]
  album?: SubsonicAlbum[]
  artist?: SubsonicArtistSummary[]
}

export interface SubsonicSimilarArtist {
  id?: string
  name: string
}

export interface SubsonicArtistInfo2 {
  biography?: string
  lastFmUrl?: string
  smallImageUrl?: string
  mediumImageUrl?: string
  largeImageUrl?: string
  similarArtist?: SubsonicSimilarArtist[]
}

export interface SubsonicPlayQueue {
  current?: string
  position?: number
  changed?: string
  changedBy?: string
  entry?: SubsonicSong[]
}

export interface StructuredLyricLine {
  start?: number
  value: string
}

export interface StructuredLyrics {
  displayArtist?: string
  displayTitle?: string
  lang?: string
  offset?: number
  synced: boolean
  line: StructuredLyricLine[]
}

interface SubsonicResponseBody {
  status: string
  version: string
  type?: string
  error?: { code: number; message: string }
  songs?: { song?: SubsonicSong[] }
  randomSongs?: { song?: SubsonicSong[] }
  albumList2?: { album?: SubsonicAlbum[] }
  album?: SubsonicAlbum
  artists?: { index?: { name: string; artist: SubsonicArtistSummary[] }[] }
  artist?: SubsonicArtist
  playlists?: { playlist?: SubsonicPlaylistSummary[] }
  playlist?: SubsonicPlaylistWithSongs
  searchResult3?: SubsonicSearchResult
  lyricsList?: { structuredLyrics?: StructuredLyrics[] }
  starred2?: SubsonicStarred2
  artistInfo2?: SubsonicArtistInfo2
  song?: SubsonicSong
  playQueue?: SubsonicPlayQueue
}

interface AuthCredentials {
  serverUrl: string
  username: string
  token: string
  salt: string
}

let _credentials: AuthCredentials | null = null

export function setCredentials(creds: AuthCredentials) {
  _credentials = creds
}

export function clearCredentials() {
  _credentials = null
}

function getAuthParams(): Record<string, string> {
  if (!_credentials) throw new Error('Not authenticated')
  const { username, token, salt } = _credentials
  return { u: username, t: token, s: salt, v: '1.16.1', c: 'resonance-web', f: 'json' }
}

type CallParams = Record<string, string | string[] | undefined>

function appendParams(url: URL, params?: CallParams) {
  Object.entries(params ?? {}).forEach(([k, v]) => {
    if (v == null) return
    if (Array.isArray(v)) v.forEach(item => url.searchParams.append(k, item))
    else url.searchParams.set(k, v)
  })
}

async function call(endpoint: string, params?: CallParams): Promise<SubsonicResponseBody> {
  if (!_credentials) throw new Error('Not authenticated')
  const url = new URL(`${_credentials.serverUrl}/rest/${endpoint}.view`)
  appendParams(url, getAuthParams())
  appendParams(url, params)
  const res = await fetch(url.toString())
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const data = await res.json()
  const body = data['subsonic-response'] as SubsonicResponseBody
  if (body.status !== 'ok') throw new Error(body.error?.message ?? 'Subsonic error')
  return body
}

export function buildUrl(endpoint: string, params?: CallParams): string {
  if (!_credentials) return ''
  const url = new URL(`${_credentials.serverUrl}/rest/${endpoint}.view`)
  appendParams(url, getAuthParams())
  appendParams(url, params)
  return url.toString()
}

export function getStreamUrl(songId: string, maxBitRate?: number, replayGain?: string): string {
  const params: Record<string, string> = { id: songId }
  if (maxBitRate && maxBitRate > 0) params.maxBitRate = String(maxBitRate)
  if (replayGain && replayGain !== 'none') params.replayGain = replayGain
  return buildUrl('stream', params)
}

export function getCoverArtUrl(coverArtId: string | undefined, size = 300): string {
  if (!coverArtId) return ''
  return buildUrl('getCoverArt', { id: coverArtId, size: String(size) })
}

export function createAuthCredentials(serverUrl: string, username: string, password: string): AuthCredentials {
  const salt = Math.random().toString(36).substring(2, 12)
  const token = md5(password + salt)
  return { serverUrl: serverUrl.replace(/\/$/, ''), username, token, salt }
}

export const subsonic = {
  ping: () => call('ping'),

  getAlbumList: (type = 'alphabeticalByName', size = 500, offset = 0) =>
    call('getAlbumList2', { type, size: String(size), offset: String(offset) }).then(
      r => r.albumList2?.album ?? [],
    ),

  getRecentAlbums: (size = 20) =>
    call('getAlbumList2', { type: 'newest', size: String(size) }).then(
      r => r.albumList2?.album ?? [],
    ),

  getAlbum: (id: string) => call('getAlbum', { id }).then(r => r.album!),

  getArtists: () =>
    call('getArtists').then(r =>
      (r.artists?.index ?? []).flatMap(i => i.artist),
    ),

  getArtist: (id: string) => call('getArtist', { id }).then(r => r.artist!),

  getPlaylists: () =>
    call('getPlaylists').then(r => r.playlists?.playlist ?? []),

  getPlaylist: (id: string) => call('getPlaylist', { id }).then(r => r.playlist!),

  search: (query: string) =>
    call('search3', { query, songCount: '100', albumCount: '20', artistCount: '20' }).then(
      r => r.searchResult3 ?? {},
    ),

  scrobble: (id: string, submission = true, timeMs?: number) =>
    call('scrobble', {
      id,
      submission: String(submission),
      ...(timeMs != null ? { time: String(timeMs) } : {}),
    }),

  getRandomSongs: (size = 50) =>
    call('getRandomSongs', { size: String(size) }).then(r => r.randomSongs?.song ?? []),

  getLyrics: (id: string): Promise<StructuredLyrics[]> =>
    call('getLyricsBySongId', { id })
      .then(r => r.lyricsList?.structuredLyrics ?? [])
      .catch(() => []),

  getSong: (id: string) => call('getSong', { id }).then(r => r.song!),

  getArtistInfo2: (id: string) =>
    call('getArtistInfo2', { id, count: '10' }).then(r => r.artistInfo2 ?? {}),

  star: (id: string) => call('star', { id }),

  unstar: (id: string) => call('unstar', { id }),

  getStarred2: () => call('getStarred2').then(r => r.starred2 ?? {}),

  createPlaylist: (name: string, songIds: string[] = []) =>
    call('createPlaylist', { name, songId: songIds }).then(r => r.playlist!),

  updatePlaylist: (
    playlistId: string,
    opts: { name?: string; songIdsToAdd?: string[]; songIndexesToRemove?: number[] },
  ) =>
    call('updatePlaylist', {
      playlistId,
      ...(opts.name != null ? { name: opts.name } : {}),
      ...(opts.songIdsToAdd?.length ? { songIdToAdd: opts.songIdsToAdd } : {}),
      ...(opts.songIndexesToRemove?.length
        ? { songIndexToRemove: opts.songIndexesToRemove.map(String) }
        : {}),
    }),

  deletePlaylist: (id: string) => call('deletePlaylist', { id }),

  getPlayQueue: () => call('getPlayQueue').then(r => r.playQueue ?? {}),

  savePlayQueue: (songIds: string[], currentId: string, positionMs: number) =>
    call('savePlayQueue', {
      id: songIds,
      current: currentId,
      position: String(Math.round(positionMs)),
    }),
}
