import { useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { Modal, ModalButton, ModalTextField } from '@/components/Modal'
import { toast } from '@/components/Toast'
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
          : 'bg-primary text-on-primary shadow-elevation-2'
        }`}
    >
      <Icon name={icon} size={16} />
      {label}
    </button>
  )
}

function IconBtn({ icon, onClick, title, danger = false }: {
  icon: string; onClick: () => void; title: string; danger?: boolean
}) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      title={title}
      aria-label={title}
      className={`ripple-root w-10 h-10 rounded-full flex items-center justify-center
        transition-colors duration-150
        ${danger
          ? 'text-error hover:bg-error-container/40'
          : 'text-on-surface-var hover:bg-on-surface/8 hover:text-on-surface'
        }`}
    >
      <Icon name={icon} size={18} filled={false} />
    </button>
  )
}

export function PlaylistDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const play = usePlayerStore(s => s.play)
  const addToQueue = usePlayerStore(s => s.addToQueue)

  const [showRename, setShowRename] = useState(false)
  const [showDelete, setShowDelete] = useState(false)
  const [renameValue, setRenameValue] = useState('')
  const selection = useSelection()
  const [selectMode, setSelectMode] = useState(false)
  const relay = useRelayShare()

  // Local working order — lets dragging feel instant without waiting on a round-trip,
  // reset from the server whenever the underlying query data changes.
  const [order, setOrder] = useState<QueueSong[] | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  const { data: playlist, isLoading } = useQuery({
    queryKey: ['playlist', id],
    queryFn: () => subsonic.getPlaylist(id!),
    staleTime: 5 * 60 * 1000,
  })

  const serverSongs: QueueSong[] = (playlist?.entry ?? []).map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? '',
  }))

  useEffect(() => { setOrder(serverSongs) }, [playlist])

  const songs = order ?? serverSongs
  const downloadState = useDownloadSongs(serverSongs)

  const renameMutation = useMutation({
    mutationFn: (name: string) => subsonic.updatePlaylist(id!, { name }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playlist', id] })
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      setShowRename(false)
      toast('Playlist renamed')
    },
    onError: () => toast('Couldn\'t rename — try again'),
  })

  const deleteMutation = useMutation({
    mutationFn: () => subsonic.deletePlaylist(id!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      navigate('/playlists')
      toast('Playlist deleted')
    },
    onError: () => toast('Couldn\'t delete — try again'),
  })

  const removeMutation = useMutation({
    mutationFn: (index: number) =>
      subsonic.updatePlaylist(id!, { songIndexesToRemove: [index] }),
    onMutate: async (index: number) => {
      setOrder(prev => (prev ?? serverSongs).filter((_, i) => i !== index))
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['playlist', id] }),
    onError: () => toast('Couldn\'t remove — try again'),
  })

  const batchRemoveMutation = useMutation({
    mutationFn: (indices: number[]) =>
      subsonic.updatePlaylist(id!, { songIndexesToRemove: indices }),
    onMutate: async (indices: number[]) => {
      const indexSet = new Set(indices)
      setOrder(prev => (prev ?? serverSongs).filter((_, i) => !indexSet.has(i)))
    },
    onSuccess: () => {
      selection.clear()
      setSelectMode(false)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['playlist', id] }),
    onError: () => toast('Couldn\'t remove — try again'),
  })

  const reorderMutation = useMutation({
    mutationFn: (newOrder: QueueSong[]) =>
      subsonic.updatePlaylist(id!, {
        songIndexesToRemove: serverSongs.map((_, i) => i),
        songIdsToAdd: newOrder.map(s => s.id),
      }),
    onError: () => {
      toast('Couldn\'t save the new order — reverted')
      setOrder(serverSongs)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['playlist', id] }),
  })

  // A remove/reorder mutation sends index-based positions to the server (Subsonic's
  // updatePlaylist has no id-based removal) — those indices are only valid against whatever
  // array the server currently holds. Letting a second one fire while an earlier one (especially
  // a reorder, which replaces the whole list) is still in flight and unrefetched can make it
  // target the wrong song. Block overlapping calls rather than risk silently deleting the wrong
  // track from someone's playlist.
  const playlistMutationPending =
    removeMutation.isPending || batchRemoveMutation.isPending || reorderMutation.isPending

  // ── Pointer-based drag reorder ──────────────────────────
  function handleDragStart(e: React.PointerEvent, songId: string) {
    if (playlistMutationPending) return
    e.stopPropagation()
    setDraggingId(songId)
  }

  useEffect(() => {
    if (!draggingId) return

    // Track the live reordered array across pointermove events within this closure —
    // reading `order` state directly in onUp would see the value from drag-start time,
    // since this effect only re-runs when draggingId changes, not on every setOrder.
    let latestOrder = order ?? serverSongs

    function onMove(e: PointerEvent) {
      const el = document.elementFromPoint(e.clientX, e.clientY)?.closest<HTMLElement>('[data-song-id]')
      const targetId = el?.dataset.songId
      if (!targetId || targetId === draggingId) return
      const from = latestOrder.findIndex(s => s.id === draggingId)
      const to = latestOrder.findIndex(s => s.id === targetId)
      if (from === -1 || to === -1 || from === to) return
      const next = [...latestOrder]
      const [moved] = next.splice(from, 1)
      next.splice(to, 0, moved)
      latestOrder = next
      setOrder(next)
    }

    function onUp() {
      setDraggingId(null)
      const changed = latestOrder.some((s, i) => s.id !== serverSongs[i]?.id)
      if (changed) reorderMutation.mutate(latestOrder)
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp, { once: true })
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draggingId])

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto page-enter p-6">
        <div className="flex gap-6 items-end">
          <div className="w-44 h-44 skeleton rounded-2xl flex-shrink-0" />
          <div className="flex-1 space-y-3 pb-2">
            <div className="h-5 skeleton rounded-full w-1/4" />
            <div className="h-8 skeleton rounded-full w-2/3" />
            <div className="h-4 skeleton rounded-full w-1/3" />
            <div className="flex gap-2 mt-4">
              <div className="h-10 w-24 skeleton rounded-full" />
              <div className="h-10 w-28 skeleton rounded-full" />
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (!playlist) return (
    <div className="flex-1 flex items-center justify-center text-on-surface-var">Playlist not found</div>
  )

  const totalDur = songs.reduce((n, s) => n + (s.duration ?? 0), 0)
  const coverArt = songs[0]?.coverArt

  return (
    <div className="flex-1 overflow-y-auto page-enter" ref={listRef}>
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
            <CoverArt coverArt={coverArt} size={400} className="w-full h-full object-cover" alt={playlist.name} />
          </div>
          <div className="min-w-0 pb-1 flex-1">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="text-[11px] font-[600] text-primary uppercase tracking-[1px] mb-2">Playlist</p>
                <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.4px] leading-tight mb-1 truncate">
                  {playlist.name}
                </h1>
                <p className="text-[12px] text-outline mb-5">
                  {songs.length} songs{totalDur ? ` · ${fmtDur(totalDur)}` : ''}
                </p>
              </div>
              <div className="flex items-center gap-1 flex-shrink-0">
                <IconBtn icon="checklist" title="Select" onClick={() => setSelectMode(true)} />
                <IconBtn
                  icon="edit"
                  title="Rename"
                  onClick={() => { setRenameValue(playlist.name); setShowRename(true) }}
                />
                <IconBtn icon="delete" title="Delete playlist" danger onClick={() => setShowDelete(true)} />
              </div>
            </div>
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
                onClick={() => share(window.location.href, playlist.name)}
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
                  onClick={() => relay.shareSongs(songs, playlist.name)}
                />
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="mx-6 h-px bg-outline-var/30" />

      <AnimatePresence>
        {selectMode && (
          <SelectionToolbar
            key="selection-toolbar"
            selectedIds={selection.selected}
            songs={songs}
            onClear={() => { selection.clear(); setSelectMode(false) }}
            onRemove={ids => {
              const indices = songs.map((_, i) => i).filter(i => ids.has(songs[i].id))
              batchRemoveMutation.mutate(indices)
            }}
          />
        )}
      </AnimatePresence>

      <div className="px-3 pb-8 pt-2">
        {songs.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 gap-3 text-on-surface-var">
            <Icon name="queue_music" size={40} filled={false} className="opacity-30" />
            <p className="text-[13px]">No songs in this playlist yet</p>
          </div>
        )}
        <AnimatePresence initial={false}>
        {songs.map((song, i) => (
          <motion.div
            key={song.id}
            data-song-id={song.id}
            layout
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: draggingId === song.id ? 0.4 : 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0, transition: { duration: 0.2 } }}
            transition={{ type: 'spring', stiffness: 500, damping: 40 }}
            className="flex items-center gap-1 overflow-hidden"
          >
            {!selectMode && (
              <button
                onPointerDown={e => handleDragStart(e, song.id)}
                disabled={playlistMutationPending}
                title="Drag to reorder"
                className="p-1.5 rounded-full text-outline hover:text-on-surface-var hover:bg-on-surface/8
                  transition-colors duration-150 cursor-grab active:cursor-grabbing touch-none flex-shrink-0
                  disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <Icon name="drag_indicator" size={16} filled={false} />
              </button>
            )}
            <div className="flex-1 min-w-0">
              <SongRow
                song={song}
                index={i}
                queue={songs}
                showAlbum
                active={false}
                onRemove={selectMode || playlistMutationPending ? undefined : () => removeMutation.mutate(i)}
                selectable={selectMode}
                selected={selection.selected.has(song.id)}
                onToggleSelect={() => selection.toggle(song.id)}
              />
            </div>
          </motion.div>
        ))}
        </AnimatePresence>
      </div>

      <Modal
        open={showRename}
        onClose={() => setShowRename(false)}
        title="Rename playlist"
        footer={
          <>
            <ModalButton label="Cancel" tonal onClick={() => setShowRename(false)} />
            <ModalButton
              label="Save"
              disabled={!renameValue.trim() || renameMutation.isPending}
              onClick={() => renameMutation.mutate(renameValue.trim())}
            />
          </>
        }
      >
        <ModalTextField value={renameValue} onChange={setRenameValue} placeholder="Playlist name" autoFocus />
      </Modal>

      <Modal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        title="Delete playlist?"
        footer={
          <>
            <ModalButton label="Cancel" tonal onClick={() => setShowDelete(false)} />
            <ModalButton
              label="Delete"
              disabled={deleteMutation.isPending}
              onClick={() => deleteMutation.mutate()}
            />
          </>
        }
      >
        <p className="text-[13px] text-on-surface-var">
          "{playlist.name}" will be permanently deleted. This can't be undone.
        </p>
      </Modal>
    </div>
  )
}
