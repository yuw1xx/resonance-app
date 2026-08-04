import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { SelectionToolbar } from '@/components/SelectionToolbar'
import { useSelection } from '@/hooks/useSelection'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { share } from '@/lib/share'
import { useDownloadSongs } from '@/hooks/useDownload'
import { useRelayShare } from '@/hooks/useRelayShare'

function fmtDur(s?: number) {
  if (!s) return ''
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m} min`
}

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
          : 'bg-primary text-on-primary shadow-elevation-2 hover:shadow-elevation-3'
        }`}
    >
      <Icon name={icon} size={16} />
      {label}
    </button>
  )
}

export function AlbumDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const play = usePlayerStore(s => s.play)
  const addToQueue = usePlayerStore(s => s.addToQueue)
  const selection = useSelection()
  const [selectMode, setSelectMode] = useState(false)
  const relay = useRelayShare()

  const { data: album, isLoading } = useQuery({
    queryKey: ['album', id],
    queryFn: () => subsonic.getAlbum(id!),
    staleTime: 5 * 60 * 1000,
  })

  // Hooks must run unconditionally before any early return below, so this is derived from
  // optional-chained data rather than the post-guard `songs` used in the JSX further down.
  const songsForDownload: QueueSong[] = (album?.song ?? []).map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? album?.name ?? '',
  }))
  const downloadState = useDownloadSongs(songsForDownload)

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto page-enter">
        <div className="p-6">
          <div className="flex gap-6 items-end">
            <div className="w-44 h-44 skeleton rounded-2xl flex-shrink-0" />
            <div className="flex-1 space-y-3 pb-2">
              <div className="h-5 skeleton rounded-full w-1/3" />
              <div className="h-8 skeleton rounded-full w-2/3" />
              <div className="h-4 skeleton rounded-full w-1/2" />
              <div className="flex gap-2 mt-4">
                <div className="h-10 w-24 skeleton rounded-full" />
                <div className="h-10 w-28 skeleton rounded-full" />
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (!album) return (
    <div className="flex-1 flex items-center justify-center text-on-surface-var">Album not found</div>
  )

  const songs: QueueSong[] = (album.song ?? []).map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? album.name,
  }))

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      {/* Hero */}
      <div className="relative px-6 pt-6 pb-8" style={{
        background: 'linear-gradient(180deg, rgba(208,188,255,0.06) 0%, transparent 100%)'
      }}>
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1 text-[13px] text-on-surface-var hover:text-on-surface mb-5
            transition-colors duration-150"
        >
          <Icon name="arrow_back" size={16} />
          Back
        </button>

        <div className="flex gap-6 items-end">
          <div className="w-44 h-44 rounded-2xl overflow-hidden shadow-elevation-4 flex-shrink-0">
            <CoverArt coverArt={album.coverArt} size={400} className="w-full h-full object-cover" alt={album.name} />
          </div>
          <div className="min-w-0 pb-1">
            <p className="text-[11px] font-[600] text-primary uppercase tracking-[1px] mb-2">Album</p>
            <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.4px] leading-tight mb-1">
              {album.name}
            </h1>
            <p className="text-[14px] text-on-surface-var mb-1">{album.artist ?? 'Unknown Artist'}</p>
            <p className="text-[12px] text-outline mb-5">
              {[album.year, album.songCount != null && `${album.songCount} songs`, album.duration && fmtDur(album.duration)]
                .filter(Boolean).join(' · ')}
            </p>
            <div className="flex flex-wrap gap-2">
              <ActionBtn label="Play" icon="play_arrow" onClick={() => play(songs, 0)} />
              <ActionBtn
                label="Shuffle"
                icon="shuffle"
                tonal
                onClick={() => play([...songs].sort(() => Math.random() - 0.5), 0)}
              />
              <ActionBtn label="Add to queue" icon="add" tonal onClick={() => addToQueue(songs)} />
              <ActionBtn
                label="Share"
                icon="share"
                tonal
                onClick={() => share(window.location.href, album.name)}
              />
              <ActionBtn
                label={downloadState.downloading
                  ? `Downloading ${downloadState.progress}/${downloadState.total}`
                  : downloadState.allDownloaded ? 'Downloaded' : 'Download'}
                icon={downloadState.allDownloaded ? 'download_done' : 'download'}
                tonal
                onClick={downloadState.download}
              />
              {relay.configured && (
                <ActionBtn
                  label={relay.sharing ? `Uploading ${relay.progress}/${relay.total}` : 'Share via link'}
                  icon="ios_share"
                  tonal
                  onClick={() => relay.shareSongs(songs, album.name)}
                />
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Divider */}
      <div className="mx-6 h-px bg-outline-var/30" />

      {selectMode ? (
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
      )}

      {/* Song list */}
      <div className="px-3 pb-8 pt-2">
        {songs.map((song, i) => (
          <SongRow
            key={song.id}
            song={song}
            index={i}
            queue={songs}
            active={false}
            selectable={selectMode}
            selected={selection.selected.has(song.id)}
            onToggleSelect={() => selection.toggle(song.id)}
          />
        ))}
      </div>
    </div>
  )
}
