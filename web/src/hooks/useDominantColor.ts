import { useEffect, useRef, useState } from 'react'

const SAMPLE = 48
const cache = new Map<string, string | null>()

function extract(url: string): Promise<string | null> {
  return new Promise(resolve => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas')
        canvas.width = SAMPLE
        canvas.height = SAMPLE
        const ctx = canvas.getContext('2d')
        if (!ctx) return resolve(null)
        ctx.drawImage(img, 0, 0, SAMPLE, SAMPLE)
        const { data } = ctx.getImageData(0, 0, SAMPLE, SAMPLE)

        let bestScore = -1
        let r = 0, g = 0, b = 0
        for (let i = 0; i < data.length; i += 4) {
          const pr = data[i], pg = data[i + 1], pb = data[i + 2]
          const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb)
          const lightness = (max + min) / 2
          const sat = max === min ? 0 : (max - min) / (255 - Math.abs(2 * lightness - 255))
          // Skip near-black / near-white — favor saturated, mid-lightness pixels.
          if (lightness < 25 || lightness > 235) continue
          const score = sat * (1 - Math.abs(lightness - 128) / 128)
          if (score > bestScore) {
            bestScore = score
            r = pr; g = pg; b = pb
          }
        }
        if (bestScore < 0) return resolve(null)
        resolve(`rgb(${r}, ${g}, ${b})`)
      } catch {
        resolve(null)
      }
    }
    img.onerror = () => resolve(null)
    img.src = url
  })
}

/** Extracts a vibrant accent color from a cover-art image URL for ambient tinting.
 * Progressive enhancement only — returns null on any failure (CORS taint, load error,
 * no vivid pixels found), which callers must treat as "use the static fallback." */
export function useDominantColor(url: string | null): string | null {
  const [color, setColor] = useState<string | null>(() => (url ? cache.get(url) ?? null : null))
  const lastUrl = useRef<string | null>(null)

  useEffect(() => {
    if (!url) { setColor(null); return }
    if (cache.has(url)) { setColor(cache.get(url) ?? null); return }

    lastUrl.current = url
    let cancelled = false
    extract(url).then(result => {
      cache.set(url, result)
      if (!cancelled && lastUrl.current === url) setColor(result)
    })
    return () => { cancelled = true }
  }, [url])

  return color
}
