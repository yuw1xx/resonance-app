import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { CoverArt } from './CoverArt'
import { useRipple } from './Ripple'
import type { SubsonicAlbum } from '@/api/subsonic'

interface Props {
  album: SubsonicAlbum
  index?: number
}

export function AlbumCard({ album, index = 0 }: Props) {
  const navigate = useNavigate()
  const ripple = useRipple()

  return (
    <motion.button
      ref={ripple.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={() => navigate(`/albums/${album.id}`)}
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay: Math.min(index * 0.03, 0.3), ease: [0.05, 0.7, 0.1, 1] }}
      whileHover={{ y: -3 }}
      whileTap={{ scale: 0.97 }}
      className="ripple-root group flex flex-col gap-2.5 text-left w-full rounded-xl p-2
        bg-surface-c hover:bg-surface-high
        transition-colors duration-250 ease-md-emphasized
        shadow-none hover:shadow-elevation-2
        focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <div className="relative w-full aspect-square overflow-hidden rounded-lg bg-surface-high">
        <CoverArt
          coverArt={album.coverArt}
          size={300}
          className="w-full h-full object-cover transition-transform duration-350 ease-md-emphasized group-hover:scale-[1.04]"
          alt={album.name}
        />
        {/* Play overlay */}
        <div className="absolute inset-0 bg-scrim/0 group-hover:bg-scrim/30 transition-colors duration-250 flex items-end justify-end p-2">
          <div className="w-10 h-10 rounded-full bg-primary flex items-center justify-center
            translate-y-2 opacity-0 group-hover:translate-y-0 group-hover:opacity-100 touch-reveal
            transition-all duration-350 ease-md-emphasized shadow-elevation-3">
            <span className="material-symbols-rounded text-on-primary text-[20px]"
              style={{ fontVariationSettings: "'FILL' 1" }}>
              play_arrow
            </span>
          </div>
        </div>
      </div>
      <div className="px-1 pb-1 min-w-0">
        <p className="text-[13px] font-[500] text-on-surface truncate leading-snug">
          {album.name}
        </p>
        <p className="text-[12px] text-on-surface-var truncate leading-snug mt-0.5">
          {album.artist ?? 'Unknown Artist'}
          {album.year ? ` · ${album.year}` : ''}
        </p>
      </div>
    </motion.button>
  )
}
