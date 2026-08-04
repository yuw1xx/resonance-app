import { useEffect, useState } from 'react'
import { isCastAvailable, onCastReady } from '@/lib/cast'

/** The Cast SDK reports availability asynchronously once its script loads (or never, on
 * browsers without Cast support like Firefox/Safari) — this just makes that reactive. */
export function useCastAvailable() {
  const [available, setAvailable] = useState(isCastAvailable())

  useEffect(() => {
    if (available) return
    onCastReady(() => setAvailable(true))
  }, [available])

  return available
}
