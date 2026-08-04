import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { createAuthCredentials, setCredentials, clearCredentials, subsonic } from '@/api/subsonic'
import { checkForRemoteQueue } from '@/stores/player'

interface AuthState {
  serverUrl: string
  username: string
  token: string
  salt: string
  isAuthenticated: boolean
  login: (serverUrl: string, username: string, password: string) => Promise<void>
  logout: () => void
  restore: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      serverUrl: '',
      username: '',
      token: '',
      salt: '',
      isAuthenticated: false,

      login: async (serverUrl, username, password) => {
        const creds = createAuthCredentials(serverUrl, username, password)
        setCredentials(creds)
        await subsonic.ping()
        set({ ...creds, isAuthenticated: true })
        checkForRemoteQueue().catch(() => {})
      },

      logout: () => {
        clearCredentials()
        set({ serverUrl: '', username: '', token: '', salt: '', isAuthenticated: false })
      },

      restore: () => {
        const { serverUrl, username, token, salt, isAuthenticated } = get()
        if (isAuthenticated && serverUrl && token) {
          setCredentials({ serverUrl, username, token, salt })
          checkForRemoteQueue().catch(() => {})
        }
      },
    }),
    { name: 'resonance-auth' },
  ),
)
