import { getStreamUrl } from '@/api/subsonic'
import type { QueueSong } from '@/stores/player'

const CACHE_NAME = 'resonance-offline-audio'
const INDEX_KEY = 'resonance-offline-index'

export interface OfflineEntry {
  songId: string
  title: string
  artist: string
  album: string
  coverArt?: string
  duration?: number
  downloadedAt: number
}

function cacheKeyFor(songId: string) {
  // Origin-relative and independent of bitrate/replayGain query params, so a download made
  // under one quality setting is still found regardless of what the setting is now.
  return `/offline-audio/${songId}`
}

function readIndex(): OfflineEntry[] {
  try {
    const raw = localStorage.getItem(INDEX_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeIndex(entries: OfflineEntry[]) {
  try { localStorage.setItem(INDEX_KEY, JSON.stringify(entries)) } catch { /* ignore */ }
}

export function getOfflineIndex(): OfflineEntry[] {
  return readIndex()
}

export function isDownloaded(songId: string): boolean {
  return readIndex().some(e => e.songId === songId)
}

export function supportsOfflineDownloads(): boolean {
  return typeof window !== 'undefined' && 'caches' in window
}

/* ─── Playback integration ───────────────────────────────────────────────
 * `stores/player.ts`'s hot playback path (crossfade/gapless engine) is synchronous end to
 * end, and Cache API lookups are inherently async — rather than threading await through
 * that carefully-sequenced code, downloaded songs are proactively resolved into blob: URLs
 * ahead of time (at app boot, and right after each download) into this in-memory map, which
 * `streamUrlFor` in player.ts can then check with a plain, synchronous lookup. */

const blobUrlCache = new Map<string, string>()

export function getResolvedOfflineUrl(songId: string): string | null {
  return blobUrlCache.get(songId) ?? null
}

async function resolveAndCacheBlobUrl(songId: string): Promise<void> {
  if (blobUrlCache.has(songId) || !supportsOfflineDownloads()) return
  const cache = await caches.open(CACHE_NAME)
  const match = await cache.match(cacheKeyFor(songId))
  if (!match) return
  const blob = await match.blob()
  blobUrlCache.set(songId, URL.createObjectURL(blob))
}

/** Call once at app startup so downloaded songs are playable offline from the very first
 * track, not just once a background resolution pass has had time to run. */
export async function preloadOfflineIndex(): Promise<void> {
  if (!supportsOfflineDownloads()) return
  await Promise.all(readIndex().map(e => resolveAndCacheBlobUrl(e.songId)))
}

/* ─── Download management ────────────────────────────────────────────── */

export async function downloadSong(song: QueueSong, maxBitRate?: number, replayGain?: string): Promise<void> {
  if (!supportsOfflineDownloads()) throw new Error('Offline storage is not available in this browser')
  const url = getStreamUrl(song.id, maxBitRate, replayGain)
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const cache = await caches.open(CACHE_NAME)
  await cache.put(cacheKeyFor(song.id), res)

  const index = readIndex().filter(e => e.songId !== song.id)
  index.push({
    songId: song.id,
    title: song.title,
    artist: song.artist,
    album: song.album,
    coverArt: song.coverArt,
    duration: song.duration,
    downloadedAt: Date.now(),
  })
  writeIndex(index)

  await resolveAndCacheBlobUrl(song.id)
}

export async function removeDownload(songId: string): Promise<void> {
  if (supportsOfflineDownloads()) {
    const cache = await caches.open(CACHE_NAME)
    await cache.delete(cacheKeyFor(songId))
  }
  const existing = blobUrlCache.get(songId)
  if (existing) {
    URL.revokeObjectURL(existing)
    blobUrlCache.delete(songId)
  }
  writeIndex(readIndex().filter(e => e.songId !== songId))
}

export async function getStorageEstimate(): Promise<{ usageBytes: number; quotaBytes: number } | null> {
  if (!navigator.storage?.estimate) return null
  const { usage, quota } = await navigator.storage.estimate()
  return { usageBytes: usage ?? 0, quotaBytes: quota ?? 0 }
}
