import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { getStreamUrl, getCoverArtUrl, subsonic, type SubsonicSong } from '@/api/subsonic'
import { toast } from '@/components/Toast'
import { appendHistoryEntry, readHistoryLog } from '@/lib/historyLog'
import { getResolvedOfflineUrl } from '@/lib/offlineDownloads'
import * as lastfm from '@/lib/lastfm'
import { useLastFmStore } from '@/stores/lastfm'
import * as maloja from '@/lib/maloja'
import { useMalojaStore } from '@/stores/maloja'
import { useSettingsStore } from './settings'
import type { EqPreset } from './settings'

export type RepeatMode = 'none' | 'one' | 'all'

export interface QueueSong extends SubsonicSong {
  title: string
  artist: string
  album: string
}

interface PlayerState {
  queue: QueueSong[]
  currentIndex: number
  isPlaying: boolean
  position: number
  duration: number
  volume: number
  shuffle: boolean
  repeat: RepeatMode
  showQueue: boolean
  showFullscreen: boolean
  sleepTimerDeadline: number | null

  play: (songs: QueueSong[], index?: number) => void
  pause: () => void
  resume: () => void
  togglePlay: () => void
  next: () => void
  prev: () => void
  seekTo: (seconds: number) => void
  setVolume: (v: number) => void
  toggleShuffle: () => void
  cycleRepeat: () => void
  addToQueue: (songs: QueueSong[]) => void
  playNext: (songs: QueueSong[]) => void
  removeFromQueue: (index: number) => void
  jumpTo: (index: number) => void
  toggleQueue: () => void
  toggleFullscreen: () => void
  startSleepTimer: (minutes: number) => void
  cancelSleepTimer: () => void
}

/* ─── Two-deck engine (gapless preload + crossfade) ─────────
 * `audio` always points at whichever deck is currently the audible/"active" one — every
 * other module in the app that reads `audio.*` directly (SettingsPage's playbackRate, the
 * timeupdate/duration listeners below) keeps working unchanged across a deck swap, since
 * this is a live `let` binding, not a snapshot.
 * The `idle` deck holds the next track preloaded ahead of time. On a natural track boundary
 * we either swap instantly (gapless, crossfadeSeconds === 0) or ramp volumes across both
 * decks over crossfadeSeconds before swapping. Explicit user navigation (skip/prev/jump/play)
 * always cuts straight to the target on the active deck — crossfading only ever happens at a
 * track's natural end, matching how every mainstream player scopes it. */

const deckA = new Audio()
const deckB = new Audio()
deckA.preload = 'auto'
deckB.preload = 'auto'

export let audio: HTMLAudioElement = deckA
let idle: HTMLAudioElement = deckB

function swapDecks() {
  const prevActive = audio
  audio = idle
  idle = prevActive
}

/* ─── Web Audio API (EQ + shared analyser), routed through both decks ─── */

let audioCtx: AudioContext | null = null
let eqFilters: BiquadFilterNode[] = []
export let sharedAnalyser: AnalyserNode | null = null
const eqConnectedDecks = new WeakSet<HTMLAudioElement>()

const EQ_FREQS = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]

const EQ_PRESETS: Record<EqPreset, number[]> = {
  flat:        [ 0,  0,  0,  0,  0,  0,  0,  0,  0,  0],
  'bass-boost':[ 7,  6,  4,  2,  0,  0,  0,  0,  0,  0],
  vocal:       [-2, -2,  0,  2,  4,  5,  4,  2,  0, -2],
  treble:      [ 0,  0,  0,  0,  0,  0,  2,  4,  6,  7],
  classical:   [ 5,  4,  3,  0,  0,  0,  0,  2,  3,  4],
  pop:         [-1,  2,  4,  4,  2,  0, -1,  0,  1,  2],
}

function ensureEqChain() {
  if (audioCtx) return
  audioCtx = new AudioContext()

  eqFilters = EQ_FREQS.map((freq, i) => {
    const f = audioCtx!.createBiquadFilter()
    f.type = i === 0 ? 'lowshelf' : i === EQ_FREQS.length - 1 ? 'highshelf' : 'peaking'
    f.frequency.value = freq
    f.Q.value = 1.4
    f.gain.value = 0
    return f
  })

  sharedAnalyser = audioCtx.createAnalyser()
  sharedAnalyser.fftSize = 128
  sharedAnalyser.smoothingTimeConstant = 0.8

  for (let i = 0; i < eqFilters.length - 1; i++) eqFilters[i].connect(eqFilters[i + 1])
  eqFilters[eqFilters.length - 1].connect(sharedAnalyser)
  sharedAnalyser.connect(audioCtx.destination)
}

function connectDeckToEq(deck: HTMLAudioElement) {
  if (eqConnectedDecks.has(deck)) return
  const source = audioCtx!.createMediaElementSource(deck)
  source.connect(eqFilters[0])
  eqConnectedDecks.add(deck)
}

let eqAppliedOnce = false

/** The persisted EQ preset is otherwise never re-applied after a reload — applyEqPreset()
 * was only ever wired to the Settings page's own click handler, so a chosen preset would
 * silently stop affecting audio the moment the tab was reopened, even though the Settings UI
 * still showed it selected. Call this once from the first real playback gesture (play/resume,
 * both always user-initiated) so a saved preset actually takes effect again. */
function ensureEqAppliedOnGesture() {
  if (eqAppliedOnce) return
  eqAppliedOnce = true
  const preset = useSettingsStore.getState().equalizerPreset
  if (preset !== 'flat') applyEqPreset(preset)
}

export function applyEqPreset(preset: EqPreset) {
  // Always called from a user gesture (settings page click) — safe to init AudioContext here.
  // Before this is ever called, both decks play straight to the speakers, bypassing Web Audio
  // entirely (so the visualizer stays blank until EQ has been touched once — existing, intentional).
  ensureEqChain()
  connectDeckToEq(deckA)
  connectDeckToEq(deckB)
  if (audioCtx?.state === 'suspended') {
    audioCtx.resume().catch(() => {})
  }
  const gains = EQ_PRESETS[preset]
  gains.forEach((g, i) => { if (eqFilters[i]) eqFilters[i].gain.value = g })
}

/* ─── Scrobbling ─────────────────────────────────────────── */

let scrobbleTimer: ReturnType<typeof setTimeout> | null = null
let lastFmScrobbleTimer: ReturnType<typeof setTimeout> | null = null

function pingLastFmNowPlaying(song: QueueSong) {
  const { enabled, nowPlayingEnabled, sessionKey } = useLastFmStore.getState()
  if (!enabled || !nowPlayingEnabled || !sessionKey || !song.artist) return
  lastfm.updateNowPlaying(sessionKey, song.artist, song.title, song.album || undefined, song.duration).catch(() => {})
}

function scheduleLastFmScrobble(song: QueueSong, durationSeconds: number) {
  if (lastFmScrobbleTimer) { clearTimeout(lastFmScrobbleTimer); lastFmScrobbleTimer = null }
  const { enabled, sessionKey, thresholdSeconds, thresholdPercent } = useLastFmStore.getState()
  if (!enabled || !sessionKey || !song.artist) return
  // Last.fm's own rule of thumb: scrobble after 50% of the track or a fixed number of
  // seconds, whichever comes first — both sides are user-configurable here.
  const startedAtUnixSeconds = Math.floor(Date.now() / 1000)
  const delay = Math.max(5_000, Math.min(thresholdSeconds * 1000, durationSeconds * (thresholdPercent / 100) * 1000))
  lastFmScrobbleTimer = setTimeout(() => {
    lastfm.scrobble(sessionKey, song.artist, song.title, startedAtUnixSeconds, song.album || undefined, song.duration)
      .catch(() => {})
  }, delay)
}

let malojaScrobbleTimer: ReturnType<typeof setTimeout> | null = null

function scheduleMalojaScrobble(song: QueueSong, durationSeconds: number) {
  if (malojaScrobbleTimer) { clearTimeout(malojaScrobbleTimer); malojaScrobbleTimer = null }
  const { enabled, serverUrl, apiKey } = useMalojaStore.getState()
  if (!enabled || !serverUrl || !apiKey || !song.artist) return
  const { scrobbleThreshold } = useSettingsStore.getState()
  // Maloja has no separate Last.fm-style threshold settings of its own — reuse the same
  // Navidrome scrobble-threshold percentage that already governs the local history log.
  const startedAtUnixSeconds = Math.floor(Date.now() / 1000)
  const delay = Math.max(30_000, Math.min(durationSeconds * (scrobbleThreshold / 100) * 1000, 240_000))
  malojaScrobbleTimer = setTimeout(() => {
    maloja.scrobble(serverUrl, apiKey, song.artist, song.title, startedAtUnixSeconds, song.album || undefined, song.duration)
      .catch(() => {})
  }, delay)
}

function setMediaSession(song: QueueSong) {
  if (!('mediaSession' in navigator)) return
  const artwork = song.coverArt
    ? [96, 192, 384, 512].map(size => ({
        src: getCoverArtUrl(song.coverArt, size),
        sizes: `${size}x${size}`,
        type: 'image/jpeg',
      }))
    : []
  navigator.mediaSession.metadata = new MediaMetadata({
    title: song.title,
    artist: song.artist ?? '',
    album: song.album ?? '',
    artwork,
  })
}

function updatePositionState() {
  if (!('mediaSession' in navigator) || !('setPositionState' in navigator.mediaSession)) return
  if (!isFinite(audio.duration) || audio.duration <= 0) return
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      position: Math.min(audio.currentTime, audio.duration),
      playbackRate: audio.playbackRate || 1,
    })
  } catch {
    // Rare: browsers can throw if called with stale/inconsistent values mid-track-swap — harmless to skip.
  }
}

function scheduleScrobble(song: QueueSong, durationSeconds: number) {
  if (scrobbleTimer) { clearTimeout(scrobbleTimer); scrobbleTimer = null }
  scheduleLastFmScrobble(song, durationSeconds)
  scheduleMalojaScrobble(song, durationSeconds)
  const { navidromeScrobbling, scrobbleThreshold } = useSettingsStore.getState()
  const delay = Math.max(30_000, Math.min(durationSeconds * (scrobbleThreshold / 100) * 1000, 240_000))
  scrobbleTimer = setTimeout(() => {
    // The local history log (for the web app's own Statistics page) is independent of the
    // "Navidrome scrobbling" setting, which only controls whether plays are also submitted to
    // the server — a user may want local stats without server-side scrobbling, or vice versa.
    appendHistoryEntry({
      songId: song.id,
      title: song.title,
      artist: song.artist,
      album: song.album,
      timestampMs: Date.now(),
      durationMs: durationSeconds * 1000,
    })
    if (navidromeScrobbling) {
      subsonic.scrobble(song.id, true, Date.now()).catch(() => {})
    }
  }, delay)
}

/* ─── Cross-device queue sync (Subsonic getPlayQueue/savePlayQueue) ───── */

let lastQueueSaveAt = 0

function saveQueueNow() {
  lastQueueSaveAt = Date.now()
  const { queue, currentIndex, position } = usePlayerStore.getState()
  const song = queue[currentIndex]
  if (!song || !queue.length) return
  subsonic.savePlayQueue(queue.map(s => s.id), song.id, position * 1000).catch(() => {})
}

function maybeSaveQueue() {
  if (Date.now() - lastQueueSaveAt < 5000) return
  saveQueueNow()
}

/** Called on logout — the loaded stream URLs are baked with this session's auth token/salt,
 * so leaving them queued would either keep playing invisibly (Layout, and with it PlayerBar,
 * unmounts once isAuthenticated flips false, but the module-level <audio> elements don't) or
 * fail silently on the next skip/preload once credentials are cleared. */
export function stopAndClearQueue() {
  abortCrossfade()
  audio.pause()
  audio.removeAttribute('src')
  idle.pause()
  idle.removeAttribute('src')
  idlePreloadedIndex = null
  if (scrobbleTimer) { clearTimeout(scrobbleTimer); scrobbleTimer = null }
  usePlayerStore.setState({ queue: [], currentIndex: 0, isPlaying: false, position: 0, duration: 0 })
}

export async function checkForRemoteQueue() {
  try {
    const remote = await subsonic.getPlayQueue()
    if (!remote.entry?.length || !remote.current) return
    const local = usePlayerStore.getState()
    const localSong = local.queue[local.currentIndex]
    if (localSong?.id === remote.current) return
    const remoteSong = remote.entry.find(s => s.id === remote.current) ?? remote.entry[0]
    // Informational only for now — the toast primitive has no action button yet, so this
    // surfaces the fact rather than offering a one-tap resume.
    toast(`"${remoteSong.title}" is playing on another device`)
  } catch {
    // Server doesn't support getPlayQueue, or nothing saved — ignore.
  }
}

/* ─── Deck loading + preload bookkeeping ────────────────────────────── */

function streamUrlFor(song: QueueSong) {
  // Downloaded songs are pre-resolved into blob: URLs ahead of time (see
  // lib/offlineDownloads.ts) specifically so this stays a synchronous lookup — Cache API
  // access is inherently async, and this function sits on the hot crossfade/gapless path.
  const offline = getResolvedOfflineUrl(song.id)
  if (offline) return offline
  const { maxBitRate, replayGain } = useSettingsStore.getState()
  return getStreamUrl(song.id, maxBitRate, replayGain)
}

function loadActive(deck: HTMLAudioElement, song: QueueSong) {
  deck.src = streamUrlFor(song)
  deck.playbackRate = useSettingsStore.getState().playbackSpeed
  deck.preservesPitch = useSettingsStore.getState().preservePitch
  deck.volume = usePlayerStore.getState().volume
}

/** Applies immediately to whatever's currently playing — unlike playbackRate/preservesPitch
 * being set only at track-load time in loadActive, this setting can change mid-playback via
 * the Settings toggle and should take effect right away, matching applyEqPreset's pattern. */
export function applyPreservePitch(enabled: boolean) {
  audio.preservesPitch = enabled
  idle.preservesPitch = enabled
}

// Weights never-played songs highest (3), very-recently-played songs lowest (~0.5), rising
// back toward 3 over about 25 days — a simple recency bias, not a true "smart" ranking.
function pickSmartShuffleIndex(queue: QueueSong[], candidates: number[]): number {
  const lastPlayedAt = new Map<string, number>()
  for (const entry of readHistoryLog()) {
    const existing = lastPlayedAt.get(entry.songId)
    if (!existing || entry.timestampMs > existing) lastPlayedAt.set(entry.songId, entry.timestampMs)
  }
  const now = Date.now()
  const weights = candidates.map(i => {
    const last = lastPlayedAt.get(queue[i].id)
    if (!last) return 3
    const daysSince = (now - last) / 86_400_000
    return Math.min(3, 0.5 + daysSince * 0.1)
  })
  const totalWeight = weights.reduce((a, b) => a + b, 0)
  let r = Math.random() * totalWeight
  for (let i = 0; i < candidates.length; i++) {
    r -= weights[i]
    if (r <= 0) return candidates[i]
  }
  return candidates[candidates.length - 1]
}

function computeNextIndex(): number | null {
  const { queue, currentIndex, repeat, shuffle } = usePlayerStore.getState()
  const { queueEndBehavior, smartShuffle } = useSettingsStore.getState()
  if (!queue.length) return null
  if (repeat === 'one') return currentIndex
  let nextIndex: number
  if (shuffle) {
    const candidates = queue.map((_, i) => i).filter(i => i !== currentIndex)
    if (!candidates.length) {
      nextIndex = 0
    } else if (smartShuffle) {
      nextIndex = pickSmartShuffleIndex(queue, candidates)
    } else {
      nextIndex = candidates[Math.floor(Math.random() * candidates.length)]
    }
  } else {
    nextIndex = currentIndex + 1
  }
  if (nextIndex >= queue.length) {
    if (repeat === 'all' || queueEndBehavior === 'repeat-all') nextIndex = 0
    else return null
  }
  return nextIndex
}

let idlePreloadedIndex: number | null = null

function schedulePreload() {
  // `idle` IS the crossfade's actively-ramping "incoming" deck while a crossfade is in
  // progress — mutating its src/currentTime here would stomp on that. Every crossfade
  // completion path calls schedulePreload() itself right after, so this just defers a beat.
  if (crossfading) return
  const nextIndex = computeNextIndex()
  if (nextIndex === null) {
    idlePreloadedIndex = null
    idle.removeAttribute('src')
    return
  }
  if (idlePreloadedIndex === nextIndex) return
  const song = usePlayerStore.getState().queue[nextIndex]
  if (!song) return
  idle.pause()
  idle.currentTime = 0
  idle.volume = usePlayerStore.getState().volume
  idle.src = streamUrlFor(song)
  idle.load()
  idlePreloadedIndex = nextIndex
}

/* ─── Crossfade ──────────────────────────────────────────────────────── */

let crossfading = false
let crossfadeRaf: number | null = null

function abortCrossfade() {
  if (!crossfading) return
  crossfading = false
  if (crossfadeRaf != null) cancelAnimationFrame(crossfadeRaf)
  crossfadeRaf = null
  idle.pause()
  idle.currentTime = 0
  idle.volume = usePlayerStore.getState().volume
  audio.volume = usePlayerStore.getState().volume
}

/** Instant swap to whatever's preloaded (or freshly loaded) in the idle deck — used both as
 * the gapless (crossfadeSeconds === 0) path and as the completion step for a ramped crossfade. */
function advanceViaPreloadedDeck(targetIndex: number) {
  const song = usePlayerStore.getState().queue[targetIndex]
  if (!song) return

  const outgoing = audio
  const incoming = idle
  const masterVolume = usePlayerStore.getState().volume

  if (idlePreloadedIndex !== targetIndex) {
    incoming.src = streamUrlFor(song)
  }
  incoming.currentTime = 0
  incoming.volume = masterVolume
  incoming.playbackRate = useSettingsStore.getState().playbackSpeed
  incoming.preservesPitch = useSettingsStore.getState().preservePitch
  incoming.play().catch(() => {})

  outgoing.pause()
  outgoing.volume = masterVolume

  swapDecks()
  idlePreloadedIndex = null

  usePlayerStore.setState({ currentIndex: targetIndex, position: 0, duration: 0 })
  scheduleScrobble(song, incoming.duration || song.duration || 0)
  if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
  pingLastFmNowPlaying(song)
  setMediaSession(song)
  saveQueueNow()
  schedulePreload()
}

function beginCrossfade(durationSec: number, targetIndex: number) {
  const song = usePlayerStore.getState().queue[targetIndex]
  if (!song) return

  crossfading = true
  const outgoing = audio
  const incoming = idle

  if (idlePreloadedIndex !== targetIndex) {
    incoming.src = streamUrlFor(song)
  }
  incoming.currentTime = 0
  incoming.volume = 0
  incoming.playbackRate = useSettingsStore.getState().playbackSpeed
  incoming.preservesPitch = useSettingsStore.getState().preservePitch
  incoming.play().catch(() => { crossfading = false })

  let startTime = performance.now()

  function tick() {
    if (!crossfading) return
    if (outgoing.paused && incoming.paused) {
      // User paused mid-ramp — freeze progress instead of letting wall-clock time keep
      // advancing it, which would otherwise silently finish (and swap decks) while paused.
      startTime += 16
      crossfadeRaf = requestAnimationFrame(tick)
      return
    }
    const t = Math.min(1, (performance.now() - startTime) / (durationSec * 1000))
    const masterVolume = usePlayerStore.getState().volume
    outgoing.volume = masterVolume * (1 - t)
    incoming.volume = masterVolume * t
    if (t < 1) {
      crossfadeRaf = requestAnimationFrame(tick)
      return
    }
    crossfading = false
    crossfadeRaf = null
    outgoing.pause()
    outgoing.volume = masterVolume
    // `incoming.paused` is a synchronous, reliable read — unlike the store's `isPlaying`,
    // which lags behind actual pause state until the async 'pause' event catches up. If the
    // user paused mid-ramp (pause() calls idle.pause() directly), skip the "now playing" ping
    // and scrobble scheduling — nothing is actually audible yet.
    const stillPlaying = !incoming.paused
    swapDecks()
    idlePreloadedIndex = null
    usePlayerStore.setState({ currentIndex: targetIndex, position: 0, duration: 0 })
    if (stillPlaying) {
      scheduleScrobble(song, incoming.duration || song.duration || 0)
      if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
      pingLastFmNowPlaying(song)
    }
    setMediaSession(song)
    saveQueueNow()
    schedulePreload()
  }
  crossfadeRaf = requestAnimationFrame(tick)
}

function maybeStartCrossfade() {
  if (crossfading) return
  const { crossfadeSeconds } = useSettingsStore.getState()
  if (crossfadeSeconds <= 0) return
  if (!isFinite(audio.duration) || audio.duration <= 0) return
  const remaining = audio.duration - audio.currentTime
  if (remaining > crossfadeSeconds || remaining <= 0) return
  const nextIndex = computeNextIndex()
  if (nextIndex === null) return
  beginCrossfade(crossfadeSeconds, nextIndex)
}

/* ─── Deck event wiring ──────────────────────────────────────────────── */

let lastPositionCommitAt = 0

function bindDeckEvents(deck: HTMLAudioElement) {
  deck.addEventListener('timeupdate', () => {
    if (deck !== audio) return
    // `position` is persisted, and zustand's persist middleware re-serializes and writes the
    // whole store to localStorage on every set() — timeupdate fires ~4x/sec, so committing it
    // that often would mean synchronously writing the full queue to localStorage 4x/sec during
    // playback. Throttle the store write to ~1x/sec; crossfade timing below reads
    // deck.currentTime/duration straight off the DOM element, so its precision is unaffected.
    const now = Date.now()
    if (now - lastPositionCommitAt >= 900) {
      lastPositionCommitAt = now
      usePlayerStore.setState({ position: deck.currentTime })
      updatePositionState()
    }
    maybeStartCrossfade()
    maybeSaveQueue()
  })

  deck.addEventListener('durationchange', () => {
    if (deck !== audio) return
    usePlayerStore.setState({ duration: isFinite(deck.duration) ? deck.duration : 0 })
  })

  deck.addEventListener('play', () => {
    if (deck !== audio) return
    usePlayerStore.setState({ isPlaying: true })
  })

  deck.addEventListener('pause', () => {
    if (deck !== audio) return
    usePlayerStore.setState({ isPlaying: false })
    if (scrobbleTimer) { clearTimeout(scrobbleTimer); scrobbleTimer = null }
    saveQueueNow()
  })

  deck.addEventListener('ended', () => {
    if (deck !== audio || crossfading) return
    const nextIndex = computeNextIndex()
    if (nextIndex === null) {
      usePlayerStore.setState({ isPlaying: false })
      return
    }
    advanceViaPreloadedDeck(nextIndex)
  })

  deck.addEventListener('error', e => {
    if (deck !== audio) return
    console.warn('Audio error:', e)
    usePlayerStore.setState({ isPlaying: false })
  })
}

bindDeckEvents(deckA)
bindDeckEvents(deckB)

/* ─── Store ──────────────────────────────────────────────────────────── */

let sleepTimerHandle: ReturnType<typeof setTimeout> | null = null

export const usePlayerStore = create<PlayerState>()(
  persist(
    (set, get) => ({
      queue: [],
      currentIndex: 0,
      isPlaying: false,
      position: 0,
      duration: 0,
      volume: 1,
      shuffle: false,
      repeat: 'none',
      showQueue: false,
      showFullscreen: false,
      sleepTimerDeadline: null,

      play: (songs, index = 0) => {
        if (!songs.length) return
        ensureEqAppliedOnGesture()
        abortCrossfade()
        const song = songs[index]
        set({ queue: songs, currentIndex: index, position: 0, duration: 0 })
        loadActive(audio, song)
        audio.play().catch(err => console.warn('play() rejected:', err))
        scheduleScrobble(song, song.duration ?? 0)
        if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
        pingLastFmNowPlaying(song)
        setMediaSession(song)
        saveQueueNow()
        schedulePreload()
      },

      pause: () => {
        audio.pause()
        if (crossfading) idle.pause()
        updatePositionState()
      },

      resume: () => {
        ensureEqAppliedOnGesture()
        audio.play().catch(err => console.warn('resume() rejected:', err))
        if (crossfading) idle.play().catch(() => {})
        updatePositionState()
      },

      togglePlay: () => {
        const { isPlaying } = get()
        if (isPlaying) get().pause()
        else get().resume()
      },

      next: () => {
        abortCrossfade()
        const nextIndex = computeNextIndex()
        const { queue, repeat } = get()
        if (!queue.length) return
        if (repeat === 'one' && nextIndex === get().currentIndex) {
          audio.currentTime = 0
          audio.play().catch(() => {})
          return
        }
        if (nextIndex === null) {
          audio.pause()
          set({ isPlaying: false })
          return
        }
        advanceViaPreloadedDeck(nextIndex)
      },

      prev: () => {
        abortCrossfade()
        const { queue, currentIndex } = get()
        if (audio.currentTime > 3) {
          audio.currentTime = 0
          set({ position: 0 })
          return
        }
        const prevIndex = Math.max(0, currentIndex - 1)
        const song = queue[prevIndex]
        if (!song) return
        set({ currentIndex: prevIndex, position: 0, duration: 0 })
        loadActive(audio, song)
        audio.play().catch(() => {})
        scheduleScrobble(song, song.duration ?? 0)
        if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
        pingLastFmNowPlaying(song)
        setMediaSession(song)
        saveQueueNow()
        schedulePreload()
      },

      seekTo: (seconds) => {
        abortCrossfade()
        audio.currentTime = seconds
        set({ position: seconds })
        updatePositionState()
      },

      setVolume: (v) => {
        const clamped = Math.max(0, Math.min(1, v))
        audio.volume = clamped
        set({ volume: clamped })
      },

      toggleShuffle: () => {
        set(s => ({ shuffle: !s.shuffle }))
        schedulePreload()
      },

      cycleRepeat: () => {
        set(s => ({
          repeat: s.repeat === 'none' ? 'all' : s.repeat === 'all' ? 'one' : 'none',
        }))
        schedulePreload()
      },

      addToQueue: (songs) => {
        set(s => ({ queue: [...s.queue, ...songs] }))
        schedulePreload()
      },

      playNext: (songs) => {
        set(s => {
          const before = s.queue.slice(0, s.currentIndex + 1)
          const after = s.queue.slice(s.currentIndex + 1)
          return { queue: [...before, ...songs, ...after] }
        })
        schedulePreload()
      },

      removeFromQueue: (index) => {
        const { queue, currentIndex, isPlaying } = get()
        if (index === currentIndex) {
          // Removing the song that's actually loaded on the audio deck — advance rather than
          // just splicing the array, otherwise `queue[currentIndex]` (and the whole UI) would
          // start pointing at a different song than what's still audibly playing.
          abortCrossfade()
          const newQueue = queue.filter((_, i) => i !== index)
          if (!newQueue.length) {
            audio.pause()
            audio.removeAttribute('src')
            set({ queue: [], currentIndex: 0, isPlaying: false, position: 0, duration: 0 })
            idlePreloadedIndex = null
            idle.removeAttribute('src')
            return
          }
          const newIndex = Math.min(index, newQueue.length - 1)
          const song = newQueue[newIndex]
          set({ queue: newQueue, currentIndex: newIndex, position: 0, duration: 0 })
          loadActive(audio, song)
          if (isPlaying) audio.play().catch(() => {})
          scheduleScrobble(song, song.duration ?? 0)
          if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
          if (isPlaying) pingLastFmNowPlaying(song)
          setMediaSession(song)
          saveQueueNow()
          schedulePreload()
          return
        }
        set(s => {
          const q = s.queue.filter((_, i) => i !== index)
          const newCurrentIndex = index < s.currentIndex ? s.currentIndex - 1 : s.currentIndex
          return { queue: q, currentIndex: Math.max(0, newCurrentIndex) }
        })
        schedulePreload()
      },

      jumpTo: (index) => {
        abortCrossfade()
        const { queue } = get()
        const song = queue[index]
        if (!song) return
        set({ currentIndex: index, position: 0, duration: 0 })
        loadActive(audio, song)
        audio.play().catch(() => {})
        scheduleScrobble(song, song.duration ?? 0)
        if (useSettingsStore.getState().navidromeScrobbling) subsonic.scrobble(song.id, false).catch(() => {})
        pingLastFmNowPlaying(song)
        setMediaSession(song)
        saveQueueNow()
        schedulePreload()
      },

      toggleQueue: () => set(s => ({ showQueue: !s.showQueue, showFullscreen: false })),
      toggleFullscreen: () => set(s => ({ showFullscreen: !s.showFullscreen, showQueue: false })),

      startSleepTimer: (minutes) => {
        if (sleepTimerHandle) clearTimeout(sleepTimerHandle)
        if (minutes <= 0) { set({ sleepTimerDeadline: null }); return }
        const deadline = Date.now() + minutes * 60_000
        set({ sleepTimerDeadline: deadline })
        sleepTimerHandle = setTimeout(() => {
          get().pause()
          set({ sleepTimerDeadline: null })
        }, minutes * 60_000)
      },

      cancelSleepTimer: () => {
        if (sleepTimerHandle) { clearTimeout(sleepTimerHandle); sleepTimerHandle = null }
        set({ sleepTimerDeadline: null })
      },
    }),
    {
      name: 'resonance-player',
      // The Audio elements, timers, and action functions aren't serializable — persist only
      // the data needed to restore "what was playing," never sleepTimerDeadline (that's a
      // one-shot session timer, not something that should silently re-arm on next launch).
      partialize: (s) => ({
        queue: s.queue,
        currentIndex: s.currentIndex,
        position: s.position,
        volume: s.volume,
        shuffle: s.shuffle,
        repeat: s.repeat,
      }),
      onRehydrateStorage: () => (state) => {
        if (!state) return
        const song = state.queue[state.currentIndex]
        if (!song) return
        loadActive(audio, song)
        const onLoaded = () => {
          audio.currentTime = state.position
          audio.removeEventListener('loadedmetadata', onLoaded)
        }
        audio.addEventListener('loadedmetadata', onLoaded)
        setMediaSession(song)
        schedulePreload()
        // Deliberately not calling .play() — restoring state should never surprise-autoplay.
      },
    },
  ),
)

if ('mediaSession' in navigator) {
  navigator.mediaSession.setActionHandler('play', () => usePlayerStore.getState().resume())
  navigator.mediaSession.setActionHandler('pause', () => usePlayerStore.getState().pause())
  navigator.mediaSession.setActionHandler('nexttrack', () => usePlayerStore.getState().next())
  navigator.mediaSession.setActionHandler('previoustrack', () => usePlayerStore.getState().prev())
  navigator.mediaSession.setActionHandler('seekto', d => {
    if (d.seekTime != null) usePlayerStore.getState().seekTo(d.seekTime)
  })
}
