import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { AlbumCard } from '@/components/AlbumCard'
import { useSettingsStore } from '@/stores/settings'

const GRID: Record<string, string> = {
  compact:     'grid-cols-3 sm:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-8 gap-2',
  comfortable: 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6 gap-3',
  spacious:    'grid-cols-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-5',
}

function AlbumSkeleton() {
  return (
    <div className="flex flex-col gap-2.5 p-2">
      <div className="aspect-square skeleton rounded-lg" />
      <div className="px-1 space-y-1.5 pb-1">
        <div className="h-3 skeleton rounded-full w-4/5" />
        <div className="h-2.5 skeleton rounded-full w-3/5" />
      </div>
    </div>
  )
}

export function AlbumsPage() {
  const { defaultSort, gridDensity } = useSettingsStore()

  const { data: albums = [], isLoading } = useQuery({
    queryKey: ['albums', defaultSort],
    queryFn: () => subsonic.getAlbumList(defaultSort, 500),
    staleTime: 5 * 60 * 1000,
  })

  return (
    <div className="flex-1 overflow-y-auto p-6 page-enter">
      <div className="flex items-baseline gap-3 mb-6">
        <h1 className="text-[22px] font-[600] text-on-surface tracking-[-0.3px]">Albums</h1>
        {!isLoading && (
          <span className="text-[13px] text-outline">{albums.length.toLocaleString()}</span>
        )}
      </div>

      <div className={`grid ${GRID[gridDensity]}`}>
        {isLoading
          ? Array.from({ length: 24 }).map((_, i) => <AlbumSkeleton key={i} />)
          : albums.map((album, i) => <AlbumCard key={album.id} album={album} index={i} />)
        }
      </div>
    </div>
  )
}
