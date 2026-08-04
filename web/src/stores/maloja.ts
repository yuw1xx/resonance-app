import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface MalojaState {
  serverUrl: string
  apiKey: string
  enabled: boolean
  set: <K extends keyof Pick<MalojaState, 'serverUrl' | 'apiKey' | 'enabled'>>(
    key: K, value: MalojaState[K],
  ) => void
}

export const useMalojaStore = create<MalojaState>()(
  persist(
    (set) => ({
      serverUrl: '',
      apiKey: '',
      enabled: false,
      set: (key, value) => set({ [key]: value }),
    }),
    { name: 'resonance-maloja' },
  ),
)
