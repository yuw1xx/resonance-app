import { useState } from 'react'
import { motion } from 'framer-motion'
import { Icon } from './Icon'
import { useRipple } from './Ripple'
import { AddToPlaylistModal } from './AddToPlaylistModal'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useToggleStar } from '@/hooks/useStarred'

function ToolbarBtn({ icon, label, onClick, danger = false }: {
  icon: string; label: string; onClick: () => void; danger?: boolean
}) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      title={label}
      className={`ripple-root flex items-center gap-1.5 px-3 py-2 rounded-full text-[12px] font-[500]
        transition-colors duration-150
        ${danger
          ? 'text-error hover:bg-error-container/40'
          : 'text-on-surface-var hover:bg-on-surface/8 hover:text-on-surface'
        }`}
    >
      <Icon name={icon} size={16} filled={false} />
      <span className="hidden sm:inline">{label}</span>
    </button>
  )
}

interface Props {
  selectedIds: Set<string>
  songs: QueueSong[]
  onClear: () => void
  onRemove?: (ids: Set<string>) => void
}

export function SelectionToolbar({ selectedIds, songs, onClear, onRemove }: Props) {
  const addToQueue = usePlayerStore(s => s.addToQueue)
  const playNext = usePlayerStore(s => s.playNext)
  const toggleStar = useToggleStar()
  const [showAddToPlaylist, setShowAddToPlaylist] = useState(false)

  const selectedSongs = songs.filter(s => selectedIds.has(s.id))
  const ids = selectedSongs.map(s => s.id)

  return (
    <motion.div
      initial={{ opacity: 0, y: -12 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -12, transition: { duration: 0.15 } }}
      transition={{ type: 'spring', stiffness: 500, damping: 36 }}
      className="sticky top-0 z-10 flex items-center justify-between gap-2 px-4 py-2.5
      bg-md-bg/90 backdrop-blur-md border-b border-outline-var/20">
      <div className="flex items-center gap-3">
        <button
          onClick={onClear}
          aria-label="Clear selection"
          className="w-8 h-8 rounded-full flex items-center justify-center
            text-on-surface-var hover:bg-on-surface/8 transition-colors duration-150"
        >
          <Icon name="close" size={18} />
        </button>
        <span className="text-[15px] font-[600] text-on-surface tabular-nums">{selectedIds.size}</span>
      </div>

      <div className="flex items-center gap-0.5">
        <ToolbarBtn icon="favorite_border" label="Like" onClick={() => {
          selectedSongs.forEach(s => toggleStar.mutate({ id: s.id, starred: false }))
        }} />
        <ToolbarBtn icon="queue_play_next" label="Play next" onClick={() => playNext(selectedSongs)} />
        <ToolbarBtn icon="add_to_queue" label="Add to queue" onClick={() => addToQueue(selectedSongs)} />
        <ToolbarBtn icon="playlist_add" label="Add to playlist" onClick={() => setShowAddToPlaylist(true)} />
        {onRemove && (
          <ToolbarBtn icon="remove_circle_outline" label="Remove" danger onClick={() => onRemove(selectedIds)} />
        )}
      </div>

      <AddToPlaylistModal
        open={showAddToPlaylist}
        onClose={() => setShowAddToPlaylist(false)}
        songIds={ids}
      />
    </motion.div>
  )
}
