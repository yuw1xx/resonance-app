import { useQuery } from '@tanstack/react-query'
import { subsonic, type SubsonicAlbum } from '@/api/subsonic'
import { AlbumCard } from '@/components/AlbumCard'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'

function AlbumSkeleton() {
  return (
    <div className="flex flex-col gap-2.5 p-2">
      <div className="aspect-square w-full skeleton rounded-lg" />
      <div className="px-1 space-y-1.5 pb-1">
        <div className="h-3 skeleton rounded-full w-4/5" />
        <div className="h-2.5 skeleton rounded-full w-3/5" />
      </div>
    </div>
  )
}

function SongSkeleton() {
  return (
    <div className="flex items-center gap-3 px-3 py-2">
      <div className="w-8 h-3 skeleton rounded-full" />
      <div className="w-10 h-10 skeleton rounded-md flex-shrink-0" />
      <div className="flex-1 space-y-1.5">
        <div className="h-3 skeleton rounded-full w-2/3" />
        <div className="h-2.5 skeleton rounded-full w-1/2" />
      </div>
      <div className="h-2.5 skeleton rounded-full w-8" />
    </div>
  )
}

function AlbumGridSection({ title, albums, loading }: {
  title: string; albums: SubsonicAlbum[]; loading: boolean
}) {
  return (
    <section>
      <div className="flex items-center justify-between mb-5">
        <h2 className="text-[18px] font-[600] text-on-surface tracking-[-0.2px]">{title}</h2>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-3">
        {loading
          ? Array.from({ length: 6 }).map((_, i) => <AlbumSkeleton key={i} />)
          : albums.map((album, i) => <AlbumCard key={album.id} album={album} index={i} />)
        }
      </div>
    </section>
  )
}

export function HomePage() {
  const play = usePlayerStore(s => s.play)
  const {
    homeShowRecentlyAdded, homeShowMostPlayed, homeShowRandomMix, homeRecentlyAddedCount,
  } = useSettingsStore()

  const { data: recentAlbums = [], isLoading: loadingRecent } = useQuery({
    queryKey: ['albums-recent', homeRecentlyAddedCount],
    queryFn: () => subsonic.getRecentAlbums(homeRecentlyAddedCount),
    staleTime: 5 * 60 * 1000,
    enabled: homeShowRecentlyAdded,
  })

  const { data: mostPlayedAlbums = [], isLoading: loadingMostPlayed } = useQuery({
    queryKey: ['albums-most-played'],
    queryFn: () => subsonic.getAlbumList('frequent', 12),
    staleTime: 5 * 60 * 1000,
    enabled: homeShowMostPlayed,
  })

  const { data: randomSongs = [], isLoading: loadingSongs } = useQuery({
    queryKey: ['random-songs'],
    queryFn: () => subsonic.getRandomSongs(20),
    staleTime: 0,
    enabled: homeShowRandomMix,
  })

  const songs: QueueSong[] = randomSongs.map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? '',
  }))

  const nothingEnabled = !homeShowRecentlyAdded && !homeShowMostPlayed && !homeShowRandomMix

  return (
    <div className="flex-1 overflow-y-auto p-6 space-y-10 page-enter">
      {nothingEnabled && (
        <div className="flex flex-col items-center justify-center py-20 gap-3 text-on-surface-var">
          <Icon name="home" size={40} filled={false} className="opacity-20" />
          <p className="text-[13px]">
            All Home sections are turned off — re-enable them in Settings → Home Screen Sections.
          </p>
        </div>
      )}

      {homeShowRecentlyAdded && (
        <AlbumGridSection title="Recently Added" albums={recentAlbums} loading={loadingRecent} />
      )}

      {homeShowMostPlayed && (
        <AlbumGridSection title="Most Played" albums={mostPlayedAlbums} loading={loadingMostPlayed} />
      )}

      {homeShowRandomMix && (
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-[18px] font-[600] text-on-surface tracking-[-0.2px]">Random Mix</h2>
            {songs.length > 0 && (
              <button
                onClick={() => play(songs, 0)}
                className="flex items-center gap-1.5 px-4 py-1.5 rounded-full
                  bg-primary-container text-on-primary-container
                  text-[12px] font-[500] hover:brightness-105 transition-all duration-150"
              >
                <Icon name="shuffle" size={14} />
                Shuffle all
              </button>
            )}
          </div>
          <div className="bg-surface-c rounded-2xl overflow-hidden">
            {loadingSongs
              ? Array.from({ length: 6 }).map((_, i) => <SongSkeleton key={i} />)
              : songs.map((song, i) => (
                  <SongRow
                    key={song.id}
                    song={song}
                    index={i}
                    queue={songs}
                    showAlbumArt
                    showAlbum
                    active={false}
                  />
                ))
            }
          </div>
        </section>
      )}
    </div>
  )
}
