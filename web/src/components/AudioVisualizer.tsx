import { useEffect, useRef } from 'react'
import { sharedAnalyser } from '@/stores/player'

interface Props {
  isPlaying: boolean
  color?: string
  className?: string
  barCount?: number
}

// Safe wrapper — roundRect is Chrome 99+, Firefox 112+, Safari 15.4+
function fillBar(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  if (w <= 0 || h <= 0) return
  if (typeof (ctx as unknown as { roundRect: unknown }).roundRect === 'function') {
    ctx.beginPath()
    ;(ctx as unknown as { roundRect: (x: number, y: number, w: number, h: number, r: number) => void })
      .roundRect(x, y, w, h, r)
    ctx.fill()
  } else {
    ctx.fillRect(x, y, w, h)
  }
}

export function AudioVisualizer({ isPlaying, color = '#D0BCFF', className = '', barCount = 24 }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const rafRef = useRef<number>(0)

  // Keep canvas pixel buffer in sync with its CSS display size
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const observer = new ResizeObserver(entries => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect
        if (width > 0 && height > 0) {
          canvas.width = Math.round(width * devicePixelRatio)
          canvas.height = Math.round(height * devicePixelRatio)
        }
      }
    })
    observer.observe(canvas)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    function getColor() {
      const v = getComputedStyle(document.documentElement).getPropertyValue('--md-primary').trim()
      return v || color
    }

    function draw() {
      rafRef.current = requestAnimationFrame(draw)
      const w = canvas!.width
      const h = canvas!.height
      if (w === 0 || h === 0) return
      ctx!.clearRect(0, 0, w, h)
      const c = getColor()
      const bw = Math.max(1, Math.floor(w / barCount) - 2)

      if (!sharedAnalyser || !isPlaying) {
        for (let i = 0; i < barCount; i++) {
          const x = i * (bw + 2)
          const idleH = Math.max(2, (3 + Math.sin(Date.now() / 700 + i * 0.6) * 2)) * devicePixelRatio
          ctx!.fillStyle = c + '35'
          fillBar(ctx!, x, h - idleH, bw, idleH, 2)
        }
        return
      }

      const bufferLength = sharedAnalyser.frequencyBinCount
      const dataArray = new Uint8Array(bufferLength)
      sharedAnalyser.getByteFrequencyData(dataArray)
      const step = Math.max(1, Math.floor(bufferLength / barCount))

      for (let i = 0; i < barCount; i++) {
        let sum = 0
        for (let j = 0; j < step; j++) sum += dataArray[i * step + j]
        const avg = sum / step
        const barH = Math.max(2 * devicePixelRatio, (avg / 255) * h)
        const x = i * (bw + 2)
        const grad = ctx!.createLinearGradient(0, h, 0, h - barH)
        grad.addColorStop(0, c + 'CC')
        grad.addColorStop(1, c + '55')
        ctx!.fillStyle = grad
        fillBar(ctx!, x, h - barH, bw, barH, 2)
      }
    }

    draw()
    return () => cancelAnimationFrame(rafRef.current)
  }, [isPlaying, color, barCount])

  return <canvas ref={canvasRef} className={`w-full h-full ${className}`} />
}
