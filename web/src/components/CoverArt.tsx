import { useState } from 'react'
import { getCoverArtUrl } from '@/api/subsonic'
import { Icon } from './Icon'

interface Props {
  coverArt?: string
  size?: number
  className?: string
  alt?: string
}

export function CoverArt({ coverArt, size = 300, className = '', alt = '' }: Props) {
  const [failed, setFailed] = useState(false)
  const src = coverArt && !failed ? getCoverArtUrl(coverArt, size) : ''

  if (!src) {
    return (
      <div
        className={`bg-surface-high flex items-center justify-center text-on-surface-var/30 ${className}`}
        aria-label={alt}
      >
        <Icon name="music_note" size={Math.min(48, Math.floor(size * 0.35))} className="text-outline" />
      </div>
    )
  }

  return (
    <img
      src={src}
      alt={alt}
      className={className}
      onError={() => setFailed(true)}
      loading="lazy"
      decoding="async"
    />
  )
}
