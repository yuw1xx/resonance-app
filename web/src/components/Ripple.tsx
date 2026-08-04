import { useRef, useCallback } from 'react'

export function useRipple() {
  const ref = useRef<HTMLElement>(null)

  const onPointerDown = useCallback((e: React.PointerEvent) => {
    const el = ref.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    const size = Math.max(rect.width, rect.height) * 2
    const wave = document.createElement('span')
    wave.className = 'ripple-wave'
    wave.style.cssText = [
      `width:${size}px`,
      `height:${size}px`,
      `left:${e.clientX - rect.left - size / 2}px`,
      `top:${e.clientY - rect.top - size / 2}px`,
    ].join(';')
    el.appendChild(wave)
    wave.addEventListener('animationend', () => wave.remove(), { once: true })
  }, [])

  return { ref, onPointerDown } as const
}
