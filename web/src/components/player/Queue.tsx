import { usePlayerStore } from '@/stores/player'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { AnimatePresence, motion } from 'framer-motion'

import type { QueueSong } from '@/stores/player'

function QueueItem({ song, index, active }: {
  song: QueueSong
  index: number
  active: boolean
}) {
  const { jumpTo, removeFromQueue } = usePlayerStore()
  const ripple = useRipple()

  return (
    <motion.div
      layout
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: 0, transition: { duration: 0.2 } }}
      transition={{ type: 'spring', stiffness: 500, damping: 40 }}
      ref={ripple.ref as React.Ref<HTMLDivElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={() => jumpTo(index)}
      className={`ripple-root group flex items-center gap-3 px-3 py-2.5 cursor-pointer
        transition-colors duration-150 rounded-xl mx-2 overflow-hidden
        ${active ? 'bg-primary-container/40' : 'hover:bg-on-surface/8'}`}
    >
      <div className="relative w-9 h-9 flex-shrink-0 rounded-md overflow-hidden shadow-elevation-1">
        <CoverArt coverArt={song.coverArt} size={72} className="w-full h-full object-cover" />
        {active && (
          <div className="absolute inset-0 bg-scrim/40 flex items-center justify-center">
            <Icon name="volume_up" size={14} className="text-white animate-float" />
          </div>
        )}
      </div>
      <div className="flex-1 min-w-0">
        <p className={`text-[12px] font-[500] truncate ${active ? 'text-primary' : 'text-on-surface'}`}>
          {song.title}
        </p>
        <p className="text-[11px] text-on-surface-var truncate">{song.artist ?? 'Unknown'}</p>
      </div>
      <button
        onClick={e => { e.stopPropagation(); removeFromQueue(index) }}
        className="p-1 rounded-full text-on-surface-var opacity-0 group-hover:opacity-100
          hover:bg-on-surface/12 hover:text-on-surface transition-all duration-150"
      >
        <Icon name="close" size={14} />
      </button>
    </motion.div>
  )
}

export function Queue() {
  const { queue, currentIndex, toggleQueue } = usePlayerStore()

  return (
    <div className="w-72 bg-md-surface flex flex-col animate-slide-in-right border-l border-outline-var/30">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-4">
        <div>
          <h2 className="text-[14px] font-[600] text-on-surface">Playing queue</h2>
          <p className="text-[12px] text-on-surface-var">{queue.length} songs</p>
        </div>
        <button
          onClick={toggleQueue}
          className="w-8 h-8 rounded-full flex items-center justify-center
            text-on-surface-var hover:bg-on-surface/8 transition-colors duration-150"
        >
          <Icon name="close" size={18} />
        </button>
      </div>

      <div className="w-full h-px bg-outline-var/30 mb-2" />

      <div className="flex-1 overflow-y-auto py-1">
        {queue.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-3 text-on-surface-var pb-8">
            <Icon name="queue_music" size={40} filled={false} className="opacity-30" />
            <p className="text-[13px]">Queue is empty</p>
          </div>
        ) : (
          <AnimatePresence>
            {queue.map((song, i) => (
              <QueueItem key={`${song.id}-${i}`} song={song} index={i} active={i === currentIndex} />
            ))}
          </AnimatePresence>
        )}
      </div>
    </div>
  )
}
