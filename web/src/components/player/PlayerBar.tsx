import { useEffect, useState } from 'react'
import { usePlayerStore } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { useCastAvailable } from '@/hooks/useCast'
import { usePlaybackPosition } from '@/hooks/usePlaybackPosition'
import { requestCastSession, endCastSession, castSong, isCasting as checkIsCasting } from '@/lib/cast'

function fmt(s: number) {
  return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`
}

function MiniBtn({
  icon, active = false, onClick, title, size = 22,
}: {
  icon: string; active?: boolean; onClick?: () => void; title?: string; size?: number
}) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      title={title}
      className={`ripple-root w-9 h-9 flex items-center justify-center rounded-full flex-shrink-0
        transition-colors duration-150
        ${active
          ? 'text-primary'
          : 'text-on-surface-var hover:text-on-surface hover:bg-on-surface/8'
        }`}
    >
      <Icon name={icon} size={size} filled={active} />
    </button>
  )
}

export function PlayerBar() {
  const {
    queue, currentIndex, isPlaying, duration,
    volume, shuffle, repeat, showQueue,
    togglePlay, next, prev, seekTo, setVolume,
    toggleShuffle, cycleRepeat, toggleQueue, toggleFullscreen,
  } = usePlayerStore()
  const position = usePlaybackPosition()

  const infoRipple = useRipple()
  const castAvailable = useCastAvailable()
  const [casting, setCasting] = useState(false)
  const { maxBitRate, replayGain } = useSettingsStore()

  const song = queue[currentIndex]

  useEffect(() => {
    if (casting && song) castSong(song, maxBitRate, replayGain)
    // Only re-cast when the track actually changes, not on every bitrate/replayGain render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [casting, song?.id])

  if (!song) return null

  const pct = duration > 0 ? (position / duration) * 100 : 0

  async function toggleCast() {
    if (casting) {
      endCastSession()
      setCasting(false)
      return
    }
    const connected = await requestCastSession()
    setCasting(connected || checkIsCasting())
  }

  return (
    <div className="flex-shrink-0 bg-surface-c border-t border-outline-var/20">

      {/* ── Seek bar ────────────────────────────────────────── */}
      <input
        type="range"
        min={0}
        max={duration || 1}
        value={position}
        onChange={e => seekTo(Number(e.target.value))}
        className="seek-bar"
        style={{ '--pct': `${pct}%` } as React.CSSProperties}
      />

      {/* ── Main row ────────────────────────────────────────── */}
      <div className="flex items-center gap-2 px-3 sm:px-4 h-[64px]">

        {/* Now-playing info — always visible, tappable to expand */}
        <button
          ref={infoRipple.ref as React.Ref<HTMLButtonElement>}
          onPointerDown={infoRipple.onPointerDown}
          onClick={toggleFullscreen}
          className="ripple-root flex items-center gap-3 min-w-0 rounded-xl p-1 -ml-1
            hover:bg-on-surface/6 transition-colors duration-150 flex-shrink-0"
          style={{ flexBasis: 'clamp(130px, 30%, 260px)', maxWidth: 'clamp(130px, 30%, 260px)' }}
        >
          <div className="w-[44px] h-[44px] flex-shrink-0 rounded-xl overflow-hidden shadow-elevation-1">
            <CoverArt coverArt={song.coverArt} size={88} className="w-full h-full object-cover" />
          </div>
          <div className="min-w-0 text-left">
            <p className="text-[13px] font-[600] text-on-surface truncate leading-snug">{song.title}</p>
            <p className="text-[11px] text-on-surface-var/70 truncate leading-snug">{song.artist ?? 'Unknown'}</p>
          </div>
        </button>

        {/* Center controls */}
        <div className="flex-1 flex items-center justify-center gap-0.5">
          <div className="hidden sm:block">
            <MiniBtn icon="shuffle" active={shuffle} onClick={toggleShuffle} title="Shuffle" />
          </div>

          <div className="hidden xs:block">
            <MiniBtn icon="skip_previous" onClick={prev} title="Previous" size={24} />
          </div>

          {/* Play FAB */}
          <button
            onClick={togglePlay}
            className="w-[44px] h-[44px] rounded-full flex items-center justify-center flex-shrink-0
              bg-primary text-on-primary mx-1
              hover:scale-[1.08] active:scale-[0.93]
              shadow-elevation-2
              transition-all duration-200 ease-md-emphasized"
            aria-label={isPlaying ? 'Pause' : 'Play'}
          >
            <Icon name={isPlaying ? 'pause' : 'play_arrow'} size={22} filled />
          </button>

          <div className="hidden xs:block">
            <MiniBtn icon="skip_next" onClick={next} title="Next" size={24} />
          </div>

          <div className="hidden sm:block">
            <MiniBtn
              icon={repeat === 'one' ? 'repeat_one' : 'repeat'}
              active={repeat !== 'none'}
              onClick={cycleRepeat}
              title="Repeat"
            />
          </div>
        </div>

        {/* Right: time + volume + queue + expand */}
        <div className="flex items-center gap-1 flex-shrink-0">
          <span className="hidden lg:block text-[11px] tabular-nums text-outline/65 mr-1 whitespace-nowrap select-none">
            {fmt(position)} / {fmt(duration)}
          </span>

          <div className="hidden lg:flex items-center gap-1.5 mr-1">
            <Icon name="volume_up" size={15} className="text-on-surface-var/45 flex-shrink-0" />
            <input
              type="range"
              min={0} max={1} step={0.01}
              value={volume}
              onChange={e => setVolume(Number(e.target.value))}
              className="volume-slider"
              style={{ width: 72, '--pct': `${volume * 100}%` } as React.CSSProperties}
            />
          </div>

          {castAvailable && (
            <div className="hidden sm:block">
              <MiniBtn
                icon={casting ? 'cast_connected' : 'cast'}
                active={casting}
                onClick={toggleCast}
                title={casting ? 'Stop casting' : 'Cast'}
                size={19}
              />
            </div>
          )}

          <div className="hidden sm:block">
            <MiniBtn icon="queue_music" active={showQueue} onClick={toggleQueue} title="Queue" />
          </div>

          <MiniBtn icon="open_in_full" onClick={toggleFullscreen} title="Expand" size={18} />
        </div>
      </div>
    </div>
  )
}
