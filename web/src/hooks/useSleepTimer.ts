import { useEffect, useState } from 'react'
import { usePlayerStore } from '@/stores/player'

/** Ticks the remaining time for the player store's one-shot sleepTimerDeadline.
 * The deadline itself is ephemeral (not persisted) — set via startSleepTimer(minutes),
 * fired from a quick-picker in the fullscreen player, not auto-armed from a saved setting. */
export function useSleepTimer() {
  const deadline = usePlayerStore(s => s.sleepTimerDeadline)
  const [remainingMs, setRemainingMs] = useState<number | null>(
    deadline != null ? deadline - Date.now() : null,
  )

  useEffect(() => {
    if (deadline == null) { setRemainingMs(null); return }
    const tick = () => setRemainingMs(Math.max(0, deadline - Date.now()))
    tick()
    const interval = setInterval(tick, 1000)
    return () => clearInterval(interval)
  }, [deadline])

  return { remainingMs }
}
