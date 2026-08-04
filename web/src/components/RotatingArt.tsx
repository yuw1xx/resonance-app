import { CoverArt } from './CoverArt'

interface Props {
  coverArt?: string
  isPlaying: boolean
  size?: number
  className?: string
}

export function RotatingArt({ coverArt, isPlaying, size = 280, className = '' }: Props) {

  return (
    <div
      className={`relative flex-shrink-0 ${className}`}
      style={{ width: size, height: size }}
    >
      {/* Outer vinyl ring */}
      <div
        className="absolute inset-0 rounded-full"
        style={{
          background: 'radial-gradient(circle at 50% 50%, #2a2a2a 0%, #111 60%, #222 80%, #111 100%)',
          boxShadow: '0 0 0 2px rgba(255,255,255,0.05), 0 20px 60px rgba(0,0,0,0.8)',
          animation: isPlaying ? 'spin-vinyl 20s linear infinite' : 'none',
          animationPlayState: isPlaying ? 'running' : 'paused',
        }}
      >
        {/* Vinyl grooves */}
        {[0.75, 0.65, 0.55, 0.45].map((r, i) => (
          <div
            key={i}
            className="absolute rounded-full border border-white/5"
            style={{
              width: `${r * 100}%`,
              height: `${r * 100}%`,
              top: `${(1 - r) / 2 * 100}%`,
              left: `${(1 - r) / 2 * 100}%`,
            }}
          />
        ))}

        {/* Album art in center circle */}
        <div
          className="absolute rounded-full overflow-hidden"
          style={{
            width: '55%',
            height: '55%',
            top: '22.5%',
            left: '22.5%',
            boxShadow: 'inset 0 0 20px rgba(0,0,0,0.5)',
          }}
        >
          <CoverArt
            coverArt={coverArt}
            size={size}
            className="w-full h-full object-cover"
          />
        </div>

        {/* Center spindle */}
        <div
          className="absolute rounded-full bg-surface-high"
          style={{
            width: '8%',
            height: '8%',
            top: '46%',
            left: '46%',
            boxShadow: '0 0 0 1px rgba(255,255,255,0.1)',
          }}
        />
      </div>

      {/* Tonearm needle hint */}
      {isPlaying && (
        <div
          className="absolute -right-2 -top-2 opacity-40"
          style={{
            width: 3,
            height: size * 0.4,
            background: 'linear-gradient(to bottom, rgba(255,255,255,0.6), transparent)',
            transform: 'rotate(30deg)',
            transformOrigin: 'top right',
            borderRadius: 2,
          }}
        />
      )}
    </div>
  )
}
