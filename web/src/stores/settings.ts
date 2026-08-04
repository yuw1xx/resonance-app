import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type AudioQuality = 0 | 96 | 128 | 192 | 256 | 320
export type ReplayGain = 'none' | 'track' | 'album'
export type GridDensity = 'compact' | 'comfortable' | 'spacious'
export type ScrobbleThreshold = 30 | 50 | 80
export type DefaultSort = 'alphabeticalByName' | 'newest' | 'byYear' | 'random'
export type AccentTheme = 'purple' | 'blue' | 'green' | 'orange' | 'pink' | 'red' | 'teal'
export type PlayerBackground = 'blur' | 'gradient' | 'minimal'
export type LyricsSize = 'small' | 'medium' | 'large'
export type LyricsAlign = 'left' | 'center'
export type AnimationSpeed = 'slow' | 'normal' | 'fast' | 'off'
export type EqPreset = 'flat' | 'bass-boost' | 'vocal' | 'treble' | 'classical' | 'pop'
export type QueueEndBehavior = 'stop' | 'repeat-all'
export type PlaybackSpeed = 0.5 | 0.75 | 1 | 1.25 | 1.5 | 2
export type PlayerArtworkShape = 'rounded' | 'square' | 'circle'
export type RecentlyAddedCount = 10 | 20 | 30 | 50

interface SettingsState {
  // Playback
  maxBitRate: AudioQuality
  replayGain: ReplayGain
  navidromeScrobbling: boolean
  scrobbleThreshold: ScrobbleThreshold
  crossfadeSeconds: number
  preservePitch: boolean
  smartShuffle: boolean

  // Library
  defaultSort: DefaultSort
  gridDensity: GridDensity
  showPlayCount: boolean

  // Lyrics
  lrclibEnabled: boolean
  lyricsSize: LyricsSize
  lyricsAlign: LyricsAlign

  // Appearance
  accentTheme: AccentTheme
  customAccentColor: string | null
  animationSpeed: AnimationSpeed
  playerArtworkShape: PlayerArtworkShape
  trueBlack: boolean
  compactList: boolean

  // Player
  playerBackground: PlayerBackground
  artRotation: boolean
  showVisualizer: boolean
  queueEndBehavior: QueueEndBehavior

  // Audio
  equalizerPreset: EqPreset
  playbackSpeed: PlaybackSpeed

  // Home screen
  homeShowRecentlyAdded: boolean
  homeShowMostPlayed: boolean
  homeShowRandomMix: boolean
  homeRecentlyAddedCount: RecentlyAddedCount

  // Online services (credential fields — empty by default, never bundled)
  discogsToken: string
  relayServerUrl: string
  relayUploadToken: string

  // Utilities
  sleepTimerMinutes: number

  set: <K extends keyof Omit<SettingsState, 'set'>>(key: K, value: SettingsState[K]) => void
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      maxBitRate: 0,
      replayGain: 'none',
      navidromeScrobbling: true,
      scrobbleThreshold: 50,
      crossfadeSeconds: 0,
      preservePitch: true,
      smartShuffle: false,
      defaultSort: 'alphabeticalByName',
      gridDensity: 'comfortable',
      showPlayCount: false,
      lrclibEnabled: true,
      lyricsSize: 'medium',
      lyricsAlign: 'center',
      accentTheme: 'purple',
      customAccentColor: null,
      animationSpeed: 'normal',
      playerArtworkShape: 'rounded',
      trueBlack: false,
      compactList: false,
      playerBackground: 'blur',
      artRotation: true,
      showVisualizer: true,
      queueEndBehavior: 'stop',
      equalizerPreset: 'flat',
      playbackSpeed: 1,
      homeShowRecentlyAdded: true,
      homeShowMostPlayed: true,
      homeShowRandomMix: true,
      homeRecentlyAddedCount: 20,
      discogsToken: '',
      relayServerUrl: '',
      relayUploadToken: '',
      sleepTimerMinutes: 0,
      set: (key, value) => set({ [key]: value }),
    }),
    { name: 'resonance-settings' },
  ),
)
