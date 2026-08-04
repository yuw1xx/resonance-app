import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import {
  getOfflineIndex, getStorageEstimate, removeDownload, supportsOfflineDownloads,
} from '@/lib/offlineDownloads'
import { toast } from '@/components/Toast'
import { Modal, ModalButton } from '@/components/Modal'

function fmtBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  return `${value.toFixed(1)} ${units[i]}`
}

export function DownloadedPage() {
  const play = usePlayerStore(s => s.play)
  const queryClient = useQueryClient()
  const [showClearAll, setShowClearAll] = useState(false)
  const [clearing, setClearing] = useState(false)

  const { data: entries = [] } = useQuery({
    queryKey: ['offline-index'],
    queryFn: async () => getOfflineIndex(),
    staleTime: 0,
  })

  const { data: storage } = useQuery({
    queryKey: ['offline-storage'],
    queryFn: () => getStorageEstimate(),
    staleTime: 0,
  })

  const songs: QueueSong[] = entries.map(e => ({
    id: e.songId,
    title: e.title,
    artist: e.artist,
    album: e.album,
    coverArt: e.coverArt,
    duration: e.duration,
  }))

  async function handleRemove(songId: string) {
    try {
      await removeDownload(songId)
      queryClient.invalidateQueries({ queryKey: ['offline-index'] })
      queryClient.invalidateQueries({ queryKey: ['offline-storage'] })
      toast('Download removed')
    } catch {
      toast('Couldn\'t remove download')
    }
  }

  async function handleClearAll() {
    setClearing(true)
    try {
      await Promise.all(entries.map(e => removeDownload(e.songId)))
      queryClient.invalidateQueries({ queryKey: ['offline-index'] })
      queryClient.invalidateQueries({ queryKey: ['offline-storage'] })
      toast('All downloads removed')
    } catch {
      toast('Couldn\'t remove all downloads')
    } finally {
      setClearing(false)
      setShowClearAll(false)
    }
  }

  if (!supportsOfflineDownloads()) {
    return (
      <div className="flex-1 flex items-center justify-center text-on-surface-var p-6 text-center">
        Offline downloads aren't supported in this browser.
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto page-enter p-6">
      <div className="flex items-start justify-between gap-4 mb-1">
        <div>
          <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.5px]">Downloaded</h1>
          <p className="text-[13px] text-on-surface-var mt-1">
            Songs available for offline playback on this device.
          </p>
        </div>
        {songs.length > 0 && (
          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              onClick={() => play(songs, 0)}
              className="flex items-center gap-2 px-4 py-2 rounded-full text-[13px] font-[600]
                bg-primary text-on-primary shadow-elevation-2 hover:scale-[1.02] active:scale-[0.97]
                transition-all duration-200 ease-md-standard"
            >
              <Icon name="play_arrow" size={16} />
              Play all
            </button>
            <button
              onClick={() => setShowClearAll(true)}
              className="px-4 py-2 rounded-full text-[13px] font-[600] text-error
                hover:bg-error-container/20 transition-colors duration-150"
            >
              Clear all
            </button>
          </div>
        )}
      </div>
      {storage && storage.quotaBytes > 0 && (
        <p className="text-[12px] text-outline mb-6">
          {fmtBytes(storage.usageBytes)} used of {fmtBytes(storage.quotaBytes)} available to this site
        </p>
      )}
      {!storage && <div className="mb-6" />}

      {songs.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 gap-3 text-on-surface-var">
          <Icon name="download_done" size={40} filled={false} className="opacity-30" />
          <p className="text-[13px]">Nothing downloaded yet — use "Download" on a song, album, or playlist.</p>
        </div>
      ) : (
        <div>
          {songs.map((song, i) => (
            <SongRow
              key={song.id}
              song={song}
              index={i}
              queue={songs}
              showAlbumArt
              showAlbum
              active={false}
              onRemove={() => handleRemove(song.id)}
              removeLabel="Remove download"
            />
          ))}
        </div>
      )}

      <Modal
        open={showClearAll}
        onClose={() => setShowClearAll(false)}
        title="Clear all downloads?"
        footer={
          <>
            <ModalButton label="Cancel" tonal onClick={() => setShowClearAll(false)} />
            <ModalButton label={clearing ? 'Clearing…' : 'Clear all'} disabled={clearing} onClick={handleClearAll} />
          </>
        }
      >
        <p className="text-[13px] text-on-surface-var">
          {songs.length} downloaded {songs.length === 1 ? 'song' : 'songs'} will be removed from this device. This can't be undone.
        </p>
      </Modal>
    </div>
  )
}
