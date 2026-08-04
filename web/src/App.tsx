import { useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/stores/auth'
import { Layout } from '@/components/Layout'
import { SetupPage } from '@/pages/SetupPage'
import { LastFmCallbackPage } from '@/pages/LastFmCallbackPage'
import { RelayReceivePage } from '@/pages/RelayReceivePage'
import { HomePage } from '@/pages/HomePage'
import { SongsPage } from '@/pages/SongsPage'
import { AlbumsPage } from '@/pages/AlbumsPage'
import { AlbumDetailPage } from '@/pages/AlbumDetailPage'
import { ArtistsPage } from '@/pages/ArtistsPage'
import { ArtistDetailPage } from '@/pages/ArtistDetailPage'
import { PlaylistsPage } from '@/pages/PlaylistsPage'
import { PlaylistDetailPage } from '@/pages/PlaylistDetailPage'
import { LikedSongsPage } from '@/pages/LikedSongsPage'
import { SongDetailPage } from '@/pages/SongDetailPage'
import { DownloadedPage } from '@/pages/DownloadedPage'
import { SearchPage } from '@/pages/SearchPage'
import { SettingsPage } from '@/pages/SettingsPage'
import { StatisticsPage } from '@/pages/StatisticsPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

function AuthGate({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, restore } = useAuthStore()

  useEffect(() => {
    restore()
  }, [restore])

  if (!isAuthenticated) return <Navigate to="/setup" replace />
  return <>{children}</>
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename={import.meta.env.BASE_URL}>
        <Routes>
          <Route path="/setup" element={<SetupPage />} />
          <Route path="/lastfm-callback" element={<LastFmCallbackPage />} />
          <Route path="/relay/:token" element={<RelayReceivePage />} />
          <Route
            element={
              <AuthGate>
                <Layout />
              </AuthGate>
            }
          >
            <Route path="/" element={<HomePage />} />
            <Route path="/songs" element={<SongsPage />} />
            <Route path="/albums" element={<AlbumsPage />} />
            <Route path="/albums/:id" element={<AlbumDetailPage />} />
            <Route path="/artists" element={<ArtistsPage />} />
            <Route path="/artists/:id" element={<ArtistDetailPage />} />
            <Route path="/playlists" element={<PlaylistsPage />} />
            <Route path="/playlists/:id" element={<PlaylistDetailPage />} />
            <Route path="/liked" element={<LikedSongsPage />} />
            <Route path="/songs/:id" element={<SongDetailPage />} />
            <Route path="/downloaded" element={<DownloadedPage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/statistics" element={<StatisticsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
