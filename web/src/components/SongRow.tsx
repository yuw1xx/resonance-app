import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { CoverArt } from './CoverArt'
import { Icon } from './Icon'
import { useRipple } from './Ripple'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'
import { useStarredIds, useToggleStar } from '@/hooks/useStarred'
import { AddToPlaylistModal } from './AddToPlaylistModal'

interface Props {
  song: QueueSong
  index?: number
  queue?: QueueSong[]
  showAlbumArt?: boolean
  showAlbum?: boolean
  active?: boolean
  onRemove?: () => void
  removeLabel?: string
  selectable?: boolean
  selected?: boolean
  onToggleSelect?: () => void
}

function formatDuration(s?: number) {
  if (!s) return ''
  return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`
}

function EqBars({ isPlaying }: { isPlaying: boolean }) {
  return (
    <div className="flex items-end gap-[2px] h-4 w-4">
      {[0, 1, 2].map(i => (
        <div
          key={i}
          className="flex-1 bg-primary rounded-[1px]"
          style={{
            height: isPlaying ? undefined : '30%',
            animation: isPlaying
              ? `eq-bar ${0.8 + i * 0.15}s ease-in-out ${i * 0.1}s infinite alternate`
              : 'none',
          }}
        />
      ))}
    </div>
  )
}

export function SongRow({
  song, index = 0, queue, showAlbumArt = false, showAlbum = false, active = false, onRemove,
  removeLabel = 'Remove from playlist', selectable = false, selected = false, onToggleSelect,
}: Props) {
  const play = usePlayerStore(s => s.play)
  const addToQueue = usePlayerStore(s => s.addToQueue)
  const playNext = usePlayerStore(s => s.playNext)
  const isPlaying = usePlayerStore(s => s.isPlaying)
  const showPlayCount = useSettingsStore(s => s.showPlayCount)
  const compactList = useSettingsStore(s => s.compactList)
  const starredIds = useStarredIds()
  const toggleStar = useToggleStar()
  const isStarred = starredIds.has(song.id)
  const [showAddToPlaylist, setShowAddToPlaylist] = useState(false)
  const ripple = useRipple()
  const navigate = useNavigate()

  const handlePlay = () => play(queue ?? [song], queue ? index : 0)
  const handleClick = () => { if (selectable) onToggleSelect?.() }

  return (
    <div
      ref={ripple.ref as React.Ref<HTMLDivElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={handleClick}
      onDoubleClick={handlePlay}
      className={`ripple-root group flex items-center gap-3 px-3 rounded-xl cursor-pointer
        transition-colors duration-150 ease-md-standard
        ${compactList ? 'py-1' : 'py-2'}
        ${selected
          ? 'bg-primary-container/30 text-on-surface'
          : active
            ? 'bg-primary-container/40 text-on-primary-container'
            : 'hover:bg-on-surface/8 text-on-surface'
        }`}
    >
      {showAlbumArt && (
        <div className={`rounded-md overflow-hidden flex-shrink-0 shadow-elevation-1 ${compactList ? 'w-8 h-8' : 'w-10 h-10'}`}>
          <CoverArt coverArt={song.coverArt} size={80} className="w-full h-full object-cover" />
        </div>
      )}

      {/* Track number / eq bars / play button / selection checkbox */}
      <div className="w-8 flex-shrink-0 flex items-center justify-center">
        {selectable ? (
          <div
            className={`w-5 h-5 rounded-md border-2 flex items-center justify-center transition-colors duration-150
              ${selected ? 'bg-primary border-primary' : 'border-outline-var'}`}
          >
            {selected && <Icon name="check" size={14} className="text-on-primary" />}
          </div>
        ) : active ? (
          <EqBars isPlaying={isPlaying} />
        ) : (
          <>
            <span className="text-[12px] font-[500] text-on-surface-var group-hover:hidden tabular-nums">
              {(index + 1).toString().padStart(2, ' ')}
            </span>
            <button
              onClick={e => { e.stopPropagation(); handlePlay() }}
              className="hidden group-hover:flex w-7 h-7 rounded-full bg-primary/10 items-center justify-center
                hover:bg-primary/20 transition-colors duration-150"
              aria-label="Play"
            >
              <Icon name="play_arrow" size={16} className="text-primary ml-[1px]" />
            </button>
          </>
        )}
      </div>

      {/* Info */}
      <div className="flex-1 min-w-0">
        <p className={`text-[13px] font-[500] truncate leading-snug ${active ? 'text-primary' : ''}`}>
          {song.title}
        </p>
        <p className="text-[12px] text-on-surface-var truncate leading-snug">
          {song.artist ?? 'Unknown'}
          {showAlbum && song.album ? ` · ${song.album}` : ''}
          {showPlayCount && song.playCount ? ` · ${song.playCount} plays` : ''}
        </p>
      </div>

      {/* Actions */}
      {!selectable && (
      <div className={`flex items-center gap-1 flex-shrink-0 transition-opacity duration-150
        ${isStarred ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 touch-reveal'}`}>
        <motion.button
          onClick={e => { e.stopPropagation(); toggleStar.mutate({ id: song.id, starred: isStarred }) }}
          title={isStarred ? 'Unlike' : 'Like'}
          whileTap={{ scale: 0.75 }}
          className={`p-1.5 rounded-full transition-colors duration-150
            ${isStarred
              ? 'text-primary hover:bg-primary/12'
              : 'text-on-surface-var hover:bg-on-surface/12 hover:text-on-surface'
            }`}
        >
          <motion.span
            key={isStarred ? 'filled' : 'outline'}
            initial={{ scale: 0.5 }}
            animate={{ scale: 1 }}
            transition={{ type: 'spring', stiffness: 500, damping: 15 }}
            className="inline-flex"
          >
            <Icon name={isStarred ? 'favorite' : 'favorite_border'} size={16} filled={isStarred} />
          </motion.span>
        </motion.button>
        <button
          onClick={e => { e.stopPropagation(); playNext([song]) }}
          title="Play next"
          className="hidden sm:block p-1.5 rounded-full text-on-surface-var hover:bg-on-surface/12 hover:text-on-surface
            transition-colors duration-150"
        >
          <Icon name="queue_play_next" size={16} filled={false} />
        </button>
        <button
          onClick={e => { e.stopPropagation(); addToQueue([song]) }}
          title="Add to queue"
          className="hidden sm:block p-1.5 rounded-full text-on-surface-var hover:bg-on-surface/12 hover:text-on-surface
            transition-colors duration-150"
        >
          <Icon name="add_to_queue" size={16} filled={false} />
        </button>
        <button
          onClick={e => { e.stopPropagation(); setShowAddToPlaylist(true) }}
          title="Add to playlist"
          className="p-1.5 rounded-full text-on-surface-var hover:bg-on-surface/12 hover:text-on-surface
            transition-colors duration-150"
        >
          <Icon name="playlist_add" size={16} filled={false} />
        </button>
        <button
          onClick={e => { e.stopPropagation(); navigate(`/songs/${song.id}`) }}
          title="Song info"
          className="p-1.5 rounded-full text-on-surface-var hover:bg-on-surface/12 hover:text-on-surface
            transition-colors duration-150"
        >
          <Icon name="info" size={16} filled={false} />
        </button>
        {onRemove && (
          <button
            onClick={e => { e.stopPropagation(); onRemove() }}
            title={removeLabel}
            className="p-1.5 rounded-full text-on-surface-var hover:bg-error-container/40 hover:text-error
              transition-colors duration-150"
          >
            <Icon name="remove_circle_outline" size={16} filled={false} />
          </button>
        )}
      </div>
      )}

      <span className="text-[12px] text-outline tabular-nums w-9 text-right flex-shrink-0">
        {formatDuration(song.duration)}
      </span>

      <AddToPlaylistModal
        open={showAddToPlaylist}
        onClose={() => setShowAddToPlaylist(false)}
        songIds={[song.id]}
      />
    </div>
  )
}
