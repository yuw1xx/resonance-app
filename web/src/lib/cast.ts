import { getStreamUrl, getCoverArtUrl } from '@/api/subsonic'
import { mimeForSuffix } from '@/lib/relay'
import type { QueueSong } from '@/stores/player'

// The Cast Web Sender SDK (loaded via <script> in index.html) has no official TypeScript
// types shipped as a project dependency — declare just the shape this file actually touches.
declare global {
  interface Window {
    __onGCastApiAvailable?: (isAvailable: boolean) => void
    cast?: {
      framework: {
        CastContext: {
          getInstance(): {
            setOptions(opts: { receiverApplicationId: string; autoJoinPolicy: string }): void
            getCurrentSession(): CastSession | null
            requestSession(): Promise<void>
            addEventListener(type: string, cb: () => void): void
          }
        }
      }
    }
    chrome?: {
      cast: {
        media: {
          DEFAULT_MEDIA_RECEIVER_APP_ID: string
          MediaInfo: new (contentId: string, contentType: string) => CastMediaInfo
          MusicTrackMediaMetadata: new () => CastMusicMetadata
          LoadRequest: new (mediaInfo: CastMediaInfo) => unknown
        }
        Image: new (url: string) => unknown
        AutoJoinPolicy: { ORIGIN_SCOPED: string }
      }
    }
  }
}

interface CastMusicMetadata {
  title?: string
  artist?: string
  albumName?: string
  images?: unknown[]
}

interface CastMediaInfo {
  metadata?: CastMusicMetadata
}

interface CastSession {
  loadMedia(request: unknown): Promise<void>
  endSession(stopCasting: boolean): void
}

let available = false
const readyCallbacks: (() => void)[] = []

window.__onGCastApiAvailable = (isAvailable: boolean) => {
  if (!isAvailable || !window.cast || !window.chrome) return
  window.cast.framework.CastContext.getInstance().setOptions({
    receiverApplicationId: window.chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,
    autoJoinPolicy: window.chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED,
  })
  available = true
  readyCallbacks.forEach(cb => cb())
  readyCallbacks.length = 0
}

export function isCastAvailable() {
  return available
}

export function onCastReady(cb: () => void) {
  if (available) cb()
  else readyCallbacks.push(cb)
}

function getSession(): CastSession | null {
  if (!available || !window.cast) return null
  return window.cast.framework.CastContext.getInstance().getCurrentSession()
}

export function isCasting() {
  return getSession() != null
}

/** Resolves true once a session is actually established, false if the user closed the
 * device picker without choosing one — lets callers update UI state precisely rather
 * than polling, since the SDK exposes no TypeScript-friendly session-change subscription here. */
export function requestCastSession(): Promise<boolean> {
  if (!available || !window.cast) return Promise.resolve(false)
  return window.cast.framework.CastContext.getInstance()
    .requestSession()
    .then(() => true)
    .catch(() => false)
}

export function endCastSession() {
  getSession()?.endSession(true)
}

/** Loads the given song on the currently connected Cast receiver. Doesn't mirror
 * play/pause/seek to the receiver — casting the current track is the full v1 scope here. */
export function castSong(song: QueueSong, maxBitRate?: number, replayGain?: string) {
  const session = getSession()
  if (!session || !window.chrome) return
  // Cast receivers reject unrecognized content types outright, so an unknown suffix falls
  // back to the mp3 mime rather than mimeForSuffix's generic octet-stream default — a guess
  // that's at least playable is better than one the receiver refuses before even trying.
  const contentType = mimeForSuffix(song.suffix)
  const mediaInfo = new window.chrome.cast.media.MediaInfo(
    getStreamUrl(song.id, maxBitRate, replayGain),
    contentType === 'application/octet-stream' ? 'audio/mpeg' : contentType,
  )
  const metadata = new window.chrome.cast.media.MusicTrackMediaMetadata()
  metadata.title = song.title
  metadata.artist = song.artist
  metadata.albumName = song.album
  if (song.coverArt) metadata.images = [new window.chrome.cast.Image(getCoverArtUrl(song.coverArt, 512))]
  mediaInfo.metadata = metadata
  const request = new window.chrome.cast.media.LoadRequest(mediaInfo)
  session.loadMedia(request).catch(err => console.warn('Cast load failed:', err))
}
