import { useState } from 'react'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { SelectionToolbar } from '@/components/SelectionToolbar'
import { useSelection } from '@/hooks/useSelection'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useStarredSongs } from '@/hooks/useStarred'

function ActionBtn({ label, icon, onClick, tonal = false }: {
  label: string; icon: string; onClick: () => void; tonal?: boolean
}) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      className={`ripple-root flex items-center gap-2 px-5 py-2.5 rounded-full text-[13px] font-[600]
        transition-all duration-200 ease-md-standard hover:scale-[1.02] active:scale-[0.97]
        ${tonal
          ? 'bg-secondary-container text-on-secondary-container'
          : 'bg-primary text-on-primary shadow-elevation-2'
        }`}
    >
      <Icon name={icon} size={16} />
      {label}
    </button>
  )
}

export function LikedSongsPage() {
  const play = usePlayerStore(s => s.play)
  const addToQueue = usePlayerStore(s => s.addToQueue)
  const { data: starred = [], isLoading } = useStarredSongs()
  const selection = useSelection()
  const [selectMode, setSelectMode] = useState(false)

  const songs: QueueSong[] = starred.map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? '',
  }))

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      <div className="relative px-6 pt-6 pb-8" style={{
        background: 'linear-gradient(180deg, rgba(208,188,255,0.06) 0%, transparent 100%)'
      }}>
        <div className="flex gap-6 items-end">
          <div className="w-44 h-44 rounded-2xl flex-shrink-0 shadow-elevation-4
            bg-gradient-to-br from-primary-container to-secondary-container
            flex items-center justify-center">
            <Icon name="favorite" size={64} className="text-on-primary-container" filled />
          </div>
          <div className="min-w-0 pb-1">
            <p className="text-[11px] font-[600] text-primary uppercase tracking-[1px] mb-2">Playlist</p>
            <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.4px] leading-tight mb-1">
              Liked Songs
            </h1>
            <p className="text-[12px] text-outline mb-5">{songs.length} songs</p>
            {songs.length > 0 && (
              <div className="flex flex-wrap gap-2">
                <ActionBtn label="Play" icon="play_arrow" onClick={() => play(songs, 0)} />
                <ActionBtn
                  label="Shuffle"
                  icon="shuffle"
                  tonal
                  onClick={() => play([...songs].sort(() => Math.random() - 0.5), 0)}
                />
                <ActionBtn label="Add to queue" icon="add" tonal onClick={() => addToQueue(songs)} />
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="mx-6 h-px bg-outline-var/30" />

      {songs.length > 0 && (
        selectMode ? (
          <SelectionToolbar
            selectedIds={selection.selected}
            songs={songs}
            onClear={() => { selection.clear(); setSelectMode(false) }}
          />
        ) : (
          <div className="flex justify-end px-6 pt-3">
            <button
              onClick={() => setSelectMode(true)}
              className="text-[12px] font-[500] text-on-surface-var hover:text-on-surface transition-colors duration-150"
            >
              Select
            </button>
          </div>
        )
      )}

      <div className="px-3 pb-8 pt-2">
        {isLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="w-5 h-5 border-2 border-primary border-t-transparent rounded-full animate-spin" />
          </div>
        ) : songs.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 gap-3 text-on-surface-var">
            <Icon name="favorite_border" size={40} filled={false} className="opacity-30" />
            <p className="text-[13px]">Songs you like will show up here</p>
          </div>
        ) : (
          songs.map((song, i) => (
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
        )}
      </div>
    </div>
  )
}
