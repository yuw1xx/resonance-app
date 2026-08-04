import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { Lyrics } from '@/components/player/Lyrics'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useStarredIds, useToggleStar } from '@/hooks/useStarred'
import { share } from '@/lib/share'
import { useDownloadSongs } from '@/hooks/useDownload'
import { useRelayShare } from '@/hooks/useRelayShare'

function fmtDuration(s?: number) {
  if (!s) return '—'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return `${m}:${String(sec).padStart(2, '0')}`
}

function formatLabel(song: QueueSong) {
  const parts: string[] = []
  if (song.suffix) parts.push(song.suffix.toUpperCase())
  if (song.bitRate) parts.push(`${song.bitRate} kbps`)
  return parts.join(' · ') || '—'
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between py-2.5 px-1">
      <span className="text-[13px] text-on-surface-var">{label}</span>
      <span className="text-[13px] font-[500] text-on-surface">{value}</span>
    </div>
  )
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
          : 'bg-primary text-on-primary shadow-elevation-2'
        }`}
    >
      <Icon name={icon} size={16} />
      {label}
    </button>
  )
}

export function SongDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const play = usePlayerStore(s => s.play)
  const addToQueue = usePlayerStore(s => s.addToQueue)
  const starredIds = useStarredIds()
  const toggleStar = useToggleStar()
  const relay = useRelayShare()

  const { data: song, isLoading } = useQuery({
    queryKey: ['song', id],
    queryFn: () => subsonic.getSong(id!),
    staleTime: 5 * 60 * 1000,
  })

  // Hooks must run unconditionally before any early return below.
  const songsForDownload: QueueSong[] = song
    ? [{ ...song, title: song.title, artist: song.artist ?? 'Unknown Artist', album: song.album ?? '' }]
    : []
  const downloadState = useDownloadSongs(songsForDownload)

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto page-enter p-6">
        <div className="flex gap-6 items-end">
          <div className="w-44 h-44 skeleton rounded-2xl flex-shrink-0" />
          <div className="flex-1 space-y-3 pb-2">
            <div className="h-5 skeleton rounded-full w-1/3" />
            <div className="h-8 skeleton rounded-full w-2/3" />
            <div className="h-4 skeleton rounded-full w-1/2" />
          </div>
        </div>
      </div>
    )
  }

  if (!song) return (
    <div className="flex-1 flex items-center justify-center text-on-surface-var">Song not found</div>
  )

  const queueSong: QueueSong = {
    ...song,
    title: song.title,
    artist: song.artist ?? 'Unknown Artist',
    album: song.album ?? '',
  }
  const isStarred = starredIds.has(song.id)

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      <div className="relative px-6 pt-6 pb-8" style={{
        background: 'linear-gradient(180deg, rgba(208,188,255,0.06) 0%, transparent 100%)'
      }}>
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1 text-[13px] text-on-surface-var hover:text-on-surface mb-5 transition-colors duration-150"
        >
          <Icon name="arrow_back" size={16} />
          Back
        </button>

        <div className="flex gap-6 items-end">
          <div className="w-44 h-44 rounded-2xl overflow-hidden shadow-elevation-4 flex-shrink-0">
            <CoverArt coverArt={song.coverArt} size={400} className="w-full h-full object-cover" alt={song.title} />
          </div>
          <div className="min-w-0 pb-1">
            <p className="text-[11px] font-[600] text-primary uppercase tracking-[1px] mb-2">Song</p>
            <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.4px] leading-tight mb-2">
              {song.title}
            </h1>
            <div className="flex flex-wrap items-center gap-x-1.5 text-[14px] text-on-surface-var mb-5">
              {song.artistId ? (
                <button onClick={() => navigate(`/artists/${song.artistId}`)} className="hover:text-on-surface hover:underline transition-colors duration-150">
                  {song.artist ?? 'Unknown Artist'}
                </button>
              ) : (
                <span>{song.artist ?? 'Unknown Artist'}</span>
              )}
              {song.albumId && (
                <>
                  <span className="opacity-50">·</span>
                  <button onClick={() => navigate(`/albums/${song.albumId}`)} className="hover:text-on-surface hover:underline transition-colors duration-150">
                    {song.album}
                  </button>
                </>
              )}
            </div>
            <div className="flex flex-wrap gap-2">
              <ActionBtn label="Play" icon="play_arrow" onClick={() => play([queueSong], 0)} />
              <ActionBtn label="Add to queue" icon="add" tonal onClick={() => addToQueue([queueSong])} />
              <ActionBtn
                label={isStarred ? 'Liked' : 'Like'}
                icon={isStarred ? 'favorite' : 'favorite_border'}
                tonal
                onClick={() => toggleStar.mutate({ id: song.id, starred: isStarred })}
              />
              <ActionBtn
                label="Share"
                icon="share"
                tonal
                onClick={() => share(window.location.href, song.title)}
              />
              <ActionBtn
                label={downloadState.downloading
                  ? 'Downloading…'
                  : downloadState.allDownloaded ? 'Downloaded' : 'Download'}
                icon={downloadState.allDownloaded ? 'download_done' : 'download'}
                tonal
                onClick={downloadState.download}
              />
              {relay.configured && (
                <ActionBtn
                  label={relay.sharing ? 'Uploading…' : 'Share via link'}
                  icon="ios_share"
                  tonal
                  onClick={() => relay.shareSongs([queueSong], song.title)}
                />
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="mx-6 h-px bg-outline-var/30" />

      <div className="px-6 py-6 grid grid-cols-1 md:grid-cols-2 gap-8">
        <div>
          <h2 className="text-[13px] font-[600] text-on-surface-var uppercase tracking-[1px] mb-2">Details</h2>
          <div className="divide-y divide-outline-var/15">
            <InfoRow label="Duration" value={fmtDuration(song.duration)} />
            <InfoRow label="Format" value={formatLabel(queueSong)} />
            <InfoRow label="Year" value={song.year ? String(song.year) : '—'} />
            <InfoRow label="Genre" value={song.genre ?? '—'} />
            <InfoRow label="Play count" value={song.playCount != null ? String(song.playCount) : '0'} />
          </div>
        </div>

        <div className="min-h-[300px] flex flex-col bg-surface-c rounded-2xl overflow-hidden">
          <h2 className="text-[13px] font-[600] text-on-surface-var uppercase tracking-[1px] px-5 pt-4">Lyrics</h2>
          <Lyrics song={queueSong} interactive={false} />
        </div>
      </div>
    </div>
  )
}
