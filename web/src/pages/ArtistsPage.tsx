import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { useRipple } from '@/components/Ripple'
import { useSettingsStore } from '@/stores/settings'

import type { SubsonicArtistSummary } from '@/api/subsonic'

const GRID: Record<string, string> = {
  compact:     'grid-cols-3 sm:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-2',
  comfortable: 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2',
  spacious:    'grid-cols-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4',
}

function ArtistCard({ artist, index }: { artist: SubsonicArtistSummary; index: number }) {
  const navigate = useNavigate()
  const ripple = useRipple()

  return (
    <button
      ref={ripple.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={() => navigate(`/artists/${artist.id}`)}
      className="ripple-root group flex flex-col items-center gap-3 p-3 rounded-2xl
        hover:bg-surface-c transition-all duration-200 ease-md-standard"
      style={{ animationDelay: `${Math.min(index * 20, 400)}ms` }}
    >
      <div className="relative w-full aspect-square overflow-hidden rounded-full bg-surface-high
        shadow-elevation-1 group-hover:shadow-elevation-2 transition-shadow duration-200">
        <CoverArt
          coverArt={artist.coverArt}
          size={200}
          className="w-full h-full object-cover transition-transform duration-350 ease-md-emphasized group-hover:scale-[1.05]"
          alt={artist.name}
        />
      </div>
      <div className="text-center w-full">
        <p className="text-[13px] font-[500] text-on-surface truncate">{artist.name}</p>
        {artist.albumCount != null && (
          <p className="text-[12px] text-outline">
            {artist.albumCount} {artist.albumCount === 1 ? 'album' : 'albums'}
          </p>
        )}
      </div>
    </button>
  )
}

function Skeleton() {
  return (
    <div className="flex flex-col items-center gap-3 p-3">
      <div className="w-full aspect-square skeleton rounded-full" />
      <div className="space-y-1.5 w-full text-center">
        <div className="h-3 skeleton rounded-full mx-auto w-3/4" />
        <div className="h-2.5 skeleton rounded-full mx-auto w-1/2" />
      </div>
    </div>
  )
}

export function ArtistsPage() {
  const { gridDensity } = useSettingsStore()
  const { data: artists = [], isLoading } = useQuery({
    queryKey: ['artists'],
    queryFn: () => subsonic.getArtists(),
    staleTime: 5 * 60 * 1000,
  })

  return (
    <div className="flex-1 overflow-y-auto p-6 page-enter">
      <div className="flex items-baseline gap-3 mb-6">
        <h1 className="text-[22px] font-[600] text-on-surface tracking-[-0.3px]">Artists</h1>
        {!isLoading && <span className="text-[13px] text-outline">{artists.length.toLocaleString()}</span>}
      </div>
      <div className={`grid ${GRID[gridDensity]}`}>
        {isLoading
          ? Array.from({ length: 20 }).map((_, i) => <Skeleton key={i} />)
          : artists.map((a, i) => <ArtistCard key={a.id} artist={a} index={i} />)
        }
      </div>
    </div>
  )
}
