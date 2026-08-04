import { useEffect, useRef, useState } from 'react'
import { audio, usePlayerStore } from '@/stores/player'

/** A smooth, per-frame playback position for UI display — the store's own `position` field is
 * deliberately throttled to ~1x/sec (it's persisted, and writing the whole queue to
 * localStorage 4x/sec during playback would be wasteful). This reads `audio.currentTime`
 * directly via requestAnimationFrame while playing instead, so the seek bar moves
 * continuously rather than visibly stepping. Falls back to the store's position (always
 * accurate, just not continuously updated) whenever paused/seeking/changing tracks. */
export function usePlaybackPosition(): number {
  const isPlaying = usePlayerStore(s => s.isPlaying)
  const storePosition = usePlayerStore(s => s.position)
  const [position, setPosition] = useState(() => audio.currentTime)
  const rafRef = useRef<number | null>(null)

  useEffect(() => {
    if (!isPlaying) return
    function tick() {
      setPosition(audio.currentTime)
      rafRef.current = requestAnimationFrame(tick)
    }
    rafRef.current = requestAnimationFrame(tick)
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
    }
  }, [isPlaying])

  // Re-syncs on pause, seek, and track change — all of which update storePosition immediately
  // (only the continuous while-playing trickle is throttled), and on isPlaying flipping true
  // so the RAF loop above picks up from the accurate current value rather than a stale one.
  useEffect(() => {
    setPosition(audio.currentTime)
  }, [storePosition, isPlaying])

  return position
}
