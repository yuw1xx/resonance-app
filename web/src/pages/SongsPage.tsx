import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { SelectionToolbar } from '@/components/SelectionToolbar'
import { useSelection } from '@/hooks/useSelection'
import { usePlayerStore, type QueueSong } from '@/stores/player'

type SongSort = 'title' | 'artist' | 'album'

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

const SORT_OPTIONS: { label: string; value: SongSort }[] = [
  { label: 'Title', value: 'title' },
  { label: 'Artist', value: 'artist' },
  { label: 'Album', value: 'album' },
]

export function SongsPage() {
  const play = usePlayerStore(s => s.play)
  const [sort, setSort] = useState<SongSort>('title')
  const selection = useSelection()
  const [selectMode, setSelectMode] = useState(false)

  const { data: rawSongs = [], isLoading } = useQuery({
    queryKey: ['all-songs'],
    queryFn: () => subsonic.getAllSongs(),
    staleTime: 5 * 60 * 1000,
  })

  const songs: QueueSong[] = useMemo(() => {
    const mapped: QueueSong[] = rawSongs.map(s => ({
      ...s,
      title: s.title,
      artist: s.artist ?? 'Unknown Artist',
      album: s.album ?? '',
    }))
    return [...mapped].sort((a, b) => (a[sort] || '').localeCompare(b[sort] || ''))
  }, [rawSongs, sort])

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      <div className="p-6 pb-0">
        <div className="flex items-baseline gap-3 mb-4">
          <h1 className="text-[22px] font-[600] text-on-surface tracking-[-0.3px]">Songs</h1>
          {!isLoading && (
            <span className="text-[13px] text-outline">{songs.length.toLocaleString()}</span>
          )}
        </div>

        <div className="flex items-center justify-between mb-3">
          <div className="flex gap-1.5">
            {SORT_OPTIONS.map(opt => (
              <button
                key={opt.value}
                onClick={() => setSort(opt.value)}
                className={`px-3 py-1.5 rounded-full text-[12px] font-[500] transition-colors duration-150
                  ${sort === opt.value
                    ? 'bg-secondary-container text-on-secondary-container'
                    : 'text-on-surface-var hover:bg-on-surface/8'
                  }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
          {songs.length > 0 && !selectMode && (
            <div className="flex items-center gap-3">
              <button
                onClick={() => setSelectMode(true)}
                className="text-[12px] font-[500] text-on-surface-var hover:text-on-surface transition-colors duration-150"
              >
                Select
              </button>
              <button
                onClick={() => play(songs, 0)}
                className="flex items-center gap-1.5 px-4 py-1.5 rounded-full
                  bg-primary-container text-on-primary-container
                  text-[12px] font-[500] hover:brightness-105 transition-all duration-150"
              >
                <Icon name="play_arrow" size={14} />
                Play all
              </button>
            </div>
          )}
        </div>
      </div>

      {selectMode && (
        <SelectionToolbar
          selectedIds={selection.selected}
          songs={songs}
          onClear={() => { selection.clear(); setSelectMode(false) }}
        />
      )}

      <div className="px-3 pb-8 pt-2">
        {isLoading
          ? Array.from({ length: 12 }).map((_, i) => <SongSkeleton key={i} />)
          : songs.map((song, i) => (
              <SongRow
                key={song.id}
                song={song}
                index={i}
                queue={songs}
                showAlbumArt
                showAlbum
                active={false}
                selectable={selectMode}
                selected={selection.selected.has(song.id)}
                onToggleSelect={() => selection.toggle(song.id)}
              />
            ))
        }
      </div>
    </div>
  )
}
