import { useEffect, useState } from 'react'
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
  // Components like PlayerBar/FullscreenPlayer reuse the same CoverArt instance across track
  // changes rather than remounting it — without this, one song with missing/404ing artwork
  // would permanently stick the fallback icon for every song after it, valid or not.
  useEffect(() => setFailed(false), [coverArt])
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
