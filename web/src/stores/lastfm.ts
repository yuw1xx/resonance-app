import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface LastFmState {
  sessionKey: string | null
  username: string | null
  enabled: boolean
  nowPlayingEnabled: boolean
  thresholdSeconds: number
  thresholdPercent: number

  setSession: (sessionKey: string, username: string) => void
  signOut: () => void
  set: <K extends keyof Pick<LastFmState, 'enabled' | 'nowPlayingEnabled' | 'thresholdSeconds' | 'thresholdPercent'>>(
    key: K, value: LastFmState[K],
  ) => void
}

export const useLastFmStore = create<LastFmState>()(
  persist(
    (set) => ({
      sessionKey: null,
      username: null,
      enabled: true,
      nowPlayingEnabled: true,
      thresholdSeconds: 30,
      thresholdPercent: 50,

      setSession: (sessionKey, username) => set({ sessionKey, username }),
      signOut: () => set({ sessionKey: null, username: null }),
      set: (key, value) => set({ [key]: value }),
    }),
    { name: 'resonance-lastfm' },
  ),
)
