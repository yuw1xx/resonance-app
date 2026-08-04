import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './App'
import { preloadOfflineIndex } from '@/lib/offlineDownloads'

// Fire-and-forget: resolves any previously-downloaded songs into playable blob: URLs before
// the player ever needs them, so offline playback works from the very first track played.
preloadOfflineIndex().catch(() => {})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
