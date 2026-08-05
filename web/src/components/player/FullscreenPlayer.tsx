import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { usePlayerStore } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'
import { getCoverArtUrl } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { RotatingArt } from '@/components/RotatingArt'
import { AudioVisualizer } from '@/components/AudioVisualizer'
import { useStarredIds, useToggleStar } from '@/hooks/useStarred'
import { usePlaybackPosition } from '@/hooks/usePlaybackPosition'
import { useDominantColor } from '@/hooks/useDominantColor'
import { Modal, ModalButton } from '@/components/Modal'
import { Lyrics } from './Lyrics'

const SLEEP_TIMER_PRESETS = [15, 30, 45, 60]

function fmt(s: number) {
  const m = Math.floor(s / 60)
  const sec = String(Math.floor(s % 60)).padStart(2, '0')
  return `${m}:${sec}`
}

// ── Control button (shuffle, repeat, skip) ───────────────
function CtrlBtn({
  icon, onClick, active = false, size = 24, title,
}: {
  icon: string; onClick?: () => void; active?: boolean; size?: number; title?: string
}) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      title={title}
      className={`ripple-root relative w-12 h-12 flex items-center justify-center rounded-full
        transition-colors duration-200 select-none flex-shrink-0
        ${active
          ? 'text-primary hover:bg-primary/10'
          : 'text-on-surface-var/70 hover:text-on-surface hover:bg-on-surface/8'
        }`}
    >
      <Icon name={icon} size={size} filled={active} />
      {active && (
        <span className="absolute bottom-2 left-1/2 -translate-x-1/2 w-1 h-1 rounded-full bg-primary pointer-events-none" />
      )}
    </button>
  )
}

export function FullscreenPlayer() {
  const {
    queue, currentIndex, isPlaying, duration,
    volume, shuffle, repeat, showQueue, sleepTimerDeadline,
    togglePlay, next, prev, seekTo, setVolume,
    toggleShuffle, cycleRepeat, toggleFullscreen, toggleQueue,
    startSleepTimer, cancelSleepTimer,
  } = usePlayerStore()
  const position = usePlaybackPosition()

  const { artRotation, showVisualizer, sleepTimerMinutes, playerArtworkShape } = useSettingsStore()
  const navigate = useNavigate()

  // ALL hooks before any conditional return
  const closeRipple = useRipple()
  const lyricsRipple = useRipple()
  const [showLyrics, setShowLyrics] = useState(false)
  const [showSleepTimer, setShowSleepTimer] = useState(false)
  const starredIds = useStarredIds()
  const toggleStar = useToggleStar()

  const song = queue[currentIndex]
  if (!song) return null

  const isStarred = starredIds.has(song.id)

  const pct = duration > 0 ? (position / duration) * 100 : 0
  const bgUrl = song.coverArt ? getCoverArtUrl(song.coverArt, 800) : null
  const accent = useDominantColor(bgUrl)
  const sliderVars = { '--pct': `${pct}%`, ...(accent ? { '--progress-accent': accent } : {}) } as React.CSSProperties

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-md-bg animate-slide-up">

      {/* ── Atmospheric background ────────────────────────── */}
      <div key={song.id} className="absolute inset-0 overflow-hidden pointer-events-none animate-bg-fade-in">
        {bgUrl ? (
          <img
            src={bgUrl} alt="" aria-hidden
            className="absolute inset-0 w-full h-full object-cover"
            style={{ transform: 'scale(1.3)', filter: 'blur(80px) brightness(0.1) saturate(2.5)' }}
          />
        ) : null}
        {/* Color wash from the art's extracted accent — pure atmosphere, no fixed hue */}
        {accent && (
          <div
            className="absolute inset-0 animate-breathe"
            style={{
              background: `radial-gradient(ellipse 80% 60% at 50% 15%, color-mix(in srgb, ${accent} 35%, transparent), transparent 70%)`,
            }}
            aria-hidden
          />
        )}
        {/* Gradient: lighter at top (art breathes), heavier at bottom (text readable) */}
        <div className="absolute inset-0 bg-gradient-to-b from-md-bg/40 via-md-bg/70 to-md-bg/95" />
      </div>

      {/* ── Top bar ──────────────────────────────────────────── */}
      <div className="relative z-10 flex-shrink-0 flex items-center px-2 h-[60px]">
        <button
          ref={closeRipple.ref as React.Ref<HTMLButtonElement>}
          onPointerDown={closeRipple.onPointerDown}
          onClick={toggleFullscreen}
          className="ripple-root w-11 h-11 rounded-full flex items-center justify-center
            text-on-surface-var/60 hover:text-on-surface hover:bg-on-surface/8
            transition-colors duration-150"
          aria-label="Collapse player"
        >
          <Icon name="keyboard_arrow_down" size={30} />
        </button>

        <div className="flex-1 text-center select-none">
          <p className="text-[11px] font-[700] text-on-surface-var/45 uppercase tracking-[2.5px]">
            Now Playing
          </p>
        </div>

        <button
          ref={lyricsRipple.ref as React.Ref<HTMLButtonElement>}
          onPointerDown={lyricsRipple.onPointerDown}
          onClick={() => setShowLyrics(v => !v)}
          className={`ripple-root w-11 h-11 rounded-full flex items-center justify-center
            transition-colors duration-150
            ${showLyrics
              ? 'text-primary bg-primary/12'
              : 'text-on-surface-var/60 hover:text-on-surface hover:bg-on-surface/8'
            }`}
          aria-label="Lyrics"
        >
          <Icon name="lyrics" size={22} filled={showLyrics} />
        </button>
      </div>

      {/* ── Body ─────────────────────────────────────────────── */}
      <div className="relative z-10 flex-1 flex flex-col md:flex-row overflow-hidden min-h-0">

        {/* Art panel */}
        <div className="flex-shrink-0 flex items-center justify-center
          px-10 pt-2 pb-5
          md:flex-1 md:p-10">

          {/* Art wrapper — sized to fit both portrait mobile and landscape desktop */}
          <div
            className="relative"
            style={{ width: 'min(76vw, 54vh, 460px)', aspectRatio: '1 / 1' }}
          >
            {/* Ambient glow behind the art */}
            {bgUrl && (
              <div
                className="absolute pointer-events-none -z-10 opacity-30 blur-3xl rounded-full"
                style={{
                  inset: '-20%',
                  backgroundImage: `url(${bgUrl})`,
                  backgroundSize: 'cover',
                  backgroundPosition: 'center',
                }}
                aria-hidden
              />
            )}
            {accent && (
              <div
                key={song.id}
                className="absolute pointer-events-none -z-10 blur-3xl rounded-full animate-breathe animate-bg-fade-in"
                style={{
                  inset: '-14%',
                  background: `radial-gradient(circle, color-mix(in srgb, ${accent} 55%, transparent), transparent 72%)`,
                }}
                aria-hidden
              />
            )}

            {artRotation ? (
              <RotatingArt
                coverArt={song.coverArt}
                isPlaying={isPlaying}
                size={500}
                className="w-full h-full"
              />
            ) : (
              <div
                className={`w-full h-full overflow-hidden ${
                  playerArtworkShape === 'circle' ? 'rounded-full'
                    : playerArtworkShape === 'square' ? 'rounded-none'
                      : 'rounded-[28px]'
                }`}
                style={{
                  boxShadow: accent
                    ? `0 36px 90px rgba(0,0,0,0.6), 0 12px 36px color-mix(in srgb, ${accent} 30%, rgba(0,0,0,0.45))`
                    : '0 36px 90px rgba(0,0,0,0.7), 0 12px 36px rgba(0,0,0,0.45)',
                  transform: isPlaying ? 'scale(1.02)' : 'scale(1)',
                  transition: 'transform 1.4s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 600ms ease',
                }}
              >
                <CoverArt
                  coverArt={song.coverArt}
                  size={900}
                  className="w-full h-full object-cover"
                  alt={song.title}
                />
              </div>
            )}
          </div>
        </div>

        {/* Controls panel */}
        <div className={`flex-shrink-0 md:flex-1 flex flex-col min-h-0
          md:justify-center md:max-w-[500px] md:pr-10
          ${showLyrics ? 'hidden md:flex' : 'flex'}`}>

          {/* Frosted glass surface — mobile only */}
          <div
            className="
              flex-1 md:flex-none flex flex-col gap-6 overflow-y-auto
              rounded-t-[32px] md:rounded-none
              bg-surface-c/60 md:bg-transparent
              backdrop-blur-2xl md:backdrop-blur-none
              px-6 pt-7 pb-10 md:px-2 md:py-0"
          >

            {/* Song title + artist + heart */}
            <div className="flex items-start gap-3">
              <div className="flex-1 min-w-0 overflow-hidden">
                <AnimatePresence mode="wait">
                  <motion.div
                    key={song.id}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -10, transition: { duration: 0.15 } }}
                    transition={{ duration: 0.25, ease: [0.05, 0.7, 0.1, 1] }}
                  >
                    <h2
                      onClick={() => { toggleFullscreen(); navigate(`/songs/${song.id}`) }}
                      className="font-[800] text-on-surface leading-[1.1] tracking-[-0.5px] line-clamp-2
                        cursor-pointer hover:opacity-80 transition-opacity duration-150"
                      style={{ fontSize: 'clamp(22px, 5vw, 32px)' }}
                    >
                      {song.title}
                    </h2>
                    <p className="text-[15px] text-on-surface-var font-[500] mt-2 truncate">
                      {song.artist ?? 'Unknown Artist'}
                    </p>
                  </motion.div>
                </AnimatePresence>
              </div>
              <motion.button
                onClick={() => toggleStar.mutate({ id: song.id, starred: isStarred })}
                whileTap={{ scale: 0.8 }}
                className={`flex-shrink-0 mt-1 w-11 h-11 flex items-center justify-center rounded-full
                  transition-colors duration-200
                  ${isStarred
                    ? 'text-primary hover:bg-primary/10'
                    : 'text-on-surface-var/40 hover:text-primary hover:bg-primary/8'
                  }`}
                aria-label={isStarred ? 'Unlike' : 'Like'}
              >
                <motion.span
                  key={isStarred ? 'filled' : 'outline'}
                  initial={{ scale: 0.6 }}
                  animate={{ scale: 1 }}
                  transition={{ type: 'spring', stiffness: 500, damping: 15 }}
                  className="inline-flex"
                >
                  <Icon name={isStarred ? 'favorite' : 'favorite_border'} size={23} filled={isStarred} />
                </motion.span>
              </motion.button>
            </div>

            {/* Seek slider */}
            <div>
              <input
                type="range"
                min={0}
                max={duration || 1}
                value={position}
                onChange={e => seekTo(Number(e.target.value))}
                className="progress-slider w-full"
                style={sliderVars}
              />
              <div className="flex justify-between -mt-1 px-0.5">
                <span className="text-[11px] text-outline/75 tabular-nums">{fmt(position)}</span>
                <span className="text-[11px] text-outline/75 tabular-nums">{fmt(duration)}</span>
              </div>
            </div>

            {/* Main playback controls */}
            <div className="flex items-center justify-between -mx-1">
              <CtrlBtn icon="shuffle" active={shuffle} onClick={toggleShuffle} size={22} title="Shuffle" />
              <CtrlBtn icon="skip_previous" onClick={prev} size={32} title="Previous" />

              {/* Play FAB — MD3 Large FAB */}
              <button
                onClick={togglePlay}
                className="w-[72px] h-[72px] flex-shrink-0 rounded-full
                  bg-primary text-on-primary
                  flex items-center justify-center
                  shadow-elevation-3 hover:shadow-elevation-4
                  hover:scale-[1.07] active:scale-[0.95]
                  transition-all duration-200 ease-md-emphasized"
                style={accent ? {
                  boxShadow: `0 8px 32px color-mix(in srgb, ${accent} 45%, transparent)`,
                } : undefined}
                aria-label={isPlaying ? 'Pause' : 'Play'}
              >
                <Icon name={isPlaying ? 'pause' : 'play_arrow'} size={36} filled />
              </button>

              <CtrlBtn icon="skip_next" onClick={next} size={32} title="Next" />
              <CtrlBtn
                icon={repeat === 'one' ? 'repeat_one' : 'repeat'}
                active={repeat !== 'none'}
                onClick={cycleRepeat}
                size={22}
                title="Repeat"
              />
            </div>

            {/* Volume */}
            <div className="flex items-center gap-3">
              <button
                onClick={() => setVolume(volume === 0 ? 0.7 : 0)}
                className="text-on-surface-var/55 hover:text-on-surface transition-colors duration-150 flex-shrink-0"
                aria-label="Toggle mute"
              >
                <Icon
                  name={volume === 0 ? 'volume_off' : volume < 0.5 ? 'volume_down' : 'volume_up'}
                  size={20}
                  filled
                />
              </button>
              <input
                type="range"
                min={0} max={1} step={0.01}
                value={volume}
                onChange={e => setVolume(Number(e.target.value))}
                className="volume-slider flex-1"
                style={{ '--pct': `${volume * 100}%` } as React.CSSProperties}
              />
              <Icon name="volume_up" size={18} className="text-on-surface-var/25 flex-shrink-0" />
            </div>

            {/* Extras: visualizer + queue */}
            <div className="flex items-center gap-3 -mt-2">
              {showVisualizer ? (
                <div className="flex-1 h-8">
                  <AudioVisualizer isPlaying={isPlaying} barCount={28} />
                </div>
              ) : (
                <div className="flex-1" />
              )}
              <CtrlBtn
                icon="bedtime"
                active={sleepTimerDeadline != null}
                onClick={() => setShowSleepTimer(true)}
                size={20}
                title="Sleep timer"
              />
              <CtrlBtn
                icon="queue_music"
                active={showQueue}
                onClick={toggleQueue}
                size={20}
                title="Queue"
              />
            </div>

          </div>
        </div>

        {/* Lyrics pane */}
        {showLyrics && (
          <div
            className="flex-1 md:w-[360px] md:max-w-[360px] md:flex-none
              border-t border-outline-var/15 md:border-t-0 md:border-l
              flex flex-col min-h-0 animate-fade-in"
          >
            <Lyrics />
          </div>
        )}

      </div>

      <Modal
        open={showSleepTimer}
        onClose={() => setShowSleepTimer(false)}
        title="Sleep timer"
        maxWidth={340}
      >
        <div className="space-y-3 py-1">
          {sleepTimerDeadline != null && (
            <p className="text-[12px] text-on-surface-var">
              Playback pauses in {Math.max(0, Math.round((sleepTimerDeadline - Date.now()) / 60_000))} min.
            </p>
          )}
          <div className="grid grid-cols-2 gap-2">
            {SLEEP_TIMER_PRESETS.map(minutes => (
              <ModalButton
                key={minutes}
                label={`${minutes} min`}
                tonal
                onClick={() => { startSleepTimer(minutes); setShowSleepTimer(false) }}
              />
            ))}
          </div>
          {sleepTimerMinutes > 0 && !SLEEP_TIMER_PRESETS.includes(sleepTimerMinutes) && (
            <ModalButton
              label={`Default: ${sleepTimerMinutes} min`}
              tonal
              onClick={() => { startSleepTimer(sleepTimerMinutes); setShowSleepTimer(false) }}
            />
          )}
          {sleepTimerDeadline != null && (
            <ModalButton
              label="Cancel timer"
              onClick={() => { cancelSleepTimer(); setShowSleepTimer(false) }}
            />
          )}
        </div>
      </Modal>
    </div>
  )
}
