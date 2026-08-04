import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'

// Single source of truth for the app's own base path — used for Vite's asset base, the PWA
// manifest's start_url/scope/icons, and (via import.meta.env.BASE_URL at runtime) every place
// in the app that builds an absolute URL by hand, like the Last.fm auth callback and Internet
// Share links. Hosted at the root of its own subdomain (resonance.yuwixx.dev), so this stays
// '/' — change it here (App.tsx's basename reads the same env var) if that ever changes to a
// subpath deploy instead.
const BASE = '/'

export default defineConfig({
  base: BASE,
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icon.svg'],
      // Off by default in vite-plugin-pwa — without this the service worker/install prompt
      // only appear in a production build (`npm run build && npm run preview`), not `npm run dev`.
      devOptions: { enabled: true, type: 'module' },
      manifest: {
        name: 'Resonance',
        short_name: 'Resonance',
        description: 'A Material You music player for Navidrome',
        theme_color: '#6D28D9',
        background_color: '#0F0D13',
        display: 'standalone',
        start_url: BASE,
        scope: BASE,
        icons: [
          { src: `${BASE}icon.svg`, sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: `${BASE}icon.svg`, sizes: 'any', type: 'image/svg+xml', purpose: 'maskable' },
        ],
      },
      workbox: {
        // The app shell only — cover art/streams live on the user's own Navidrome server,
        // a different origin entirely, handled by runtimeCaching below instead.
        globPatterns: ['**/*.{js,css,html,svg}'],
        runtimeCaching: [
          {
            // Cover art rarely changes for a given id — cache-first saves real
            // bandwidth/battery on a media-heavy app.
            urlPattern: ({ url, sameOrigin }) => !sameOrigin && url.pathname.includes('/rest/getCoverArt'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'resonance-cover-art',
              expiration: { maxEntries: 500, maxAgeSeconds: 30 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            // Library browsing/search/playlists — network-first so it never looks stale,
            // falling back to cache when offline. Deliberately excludes /rest/stream: explicit
            // per-song "Download" (lib/offlineDownloads.ts) owns that cache, not this
            // opportunistic rule — we don't want to silently cache every song ever streamed.
            urlPattern: ({ url, sameOrigin }) =>
              !sameOrigin && url.pathname.includes('/rest/') && !url.pathname.includes('/rest/stream'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'resonance-api',
              networkTimeoutSeconds: 8,
              expiration: { maxEntries: 200, maxAgeSeconds: 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
    }),
  ],
  server: {
    host: true,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
