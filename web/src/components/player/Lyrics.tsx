import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { usePlayerStore, type QueueSong } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'
import { subsonic, type StructuredLyricLine } from '@/api/subsonic'
import { fetchLrcLibLyrics } from '@/api/lrclib'
import { Icon } from '@/components/Icon'
import type { LyricsSize } from '@/stores/settings'

type LyricLine = { timeMs: number; text: string }

function toLines(s: { line: StructuredLyricLine[]; synced: boolean } | undefined): LyricLine[] | null {
  if (!s?.synced) return null
  return s.line
    .filter(l => l.start != null)
    .map(l => ({ timeMs: l.start!, text: l.value }))
    .sort((a, b) => a.timeMs - b.timeMs)
}

const SIZE_ACTIVE: Record<LyricsSize, string> = {
  small:  'text-[16px]',
  medium: 'text-[22px]',
  large:  'text-[28px]',
}
const SIZE_REST: Record<LyricsSize, string> = {
  small:  'text-[12px]',
  medium: 'text-[15px]',
  large:  'text-[18px]',
}

interface Props {
  /** Overrides for viewing lyrics outside the now-playing context (e.g. Song Detail). */
  song?: QueueSong
  position?: number
  /** When following an arbitrary song rather than the live queue, there's nothing to auto-scroll/highlight. */
  interactive?: boolean
}

export function Lyrics({ song: songProp, position: positionProp, interactive = true }: Props = {}) {
  const { queue, currentIndex, position: livePosition } = usePlayerStore()
  const song = songProp ?? queue[currentIndex]
  const position = positionProp ?? livePosition
  const containerRef = useRef<HTMLDivElement>(null)
  const activeRef = useRef<HTMLParagraphElement>(null)
  const [activeIndex, setActiveIndex] = useState(0)

  const { lrclibEnabled, lyricsSize, lyricsAlign } = useSettingsStore()

  const { data: lines, isLoading } = useQuery<LyricLine[] | null>({
    queryKey: ['lyrics', song?.id, lrclibEnabled],
    queryFn: async (): Promise<LyricLine[] | null> => {
      if (!song) return null
      const structured = await subsonic.getLyrics(song.id)
      const synced = structured.find(l => l.synced)
      const fromNavi = toLines(synced)
      if (fromNavi?.length) return fromNavi

      if (lrclibEnabled) {
        const lrclib = await fetchLrcLibLyrics(song.title, song.artist ?? '', song.album ?? '', song.duration ?? 0)
        if (lrclib?.length) return lrclib as LyricLine[]
      }

      const plain = structured.find(l => !l.synced)
      if (plain?.line.length) return plain.line.map((l, i) => ({ timeMs: i * 3000, text: l.value }))
      return null
    },
    enabled: !!song,
    staleTime: Infinity,
  })

  useEffect(() => {
    if (!interactive || !lines) return
    const posMs = position * 1000
    let idx = 0
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].timeMs <= posMs) idx = i
      else break
    }
    setActiveIndex(idx)
  }, [interactive, position, lines])

  useEffect(() => {
    if (!interactive) return
    activeRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [interactive, activeIndex])

  if (!song) return null

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  if (!lines) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3 text-on-surface-var">
        <Icon name="lyrics" size={40} filled={false} className="opacity-30" />
        <p className="text-[13px]">No lyrics available</p>
      </div>
    )
  }

  const align = lyricsAlign === 'left' ? 'text-left' : 'text-center'
  const px = lyricsAlign === 'left' ? 'px-6' : 'px-8'

  return (
    <div ref={containerRef} className={`flex-1 overflow-y-auto ${px} py-10 space-y-4 ${align}`}>
      {lines.map((line, i) => (
        <p
          key={i}
          ref={interactive && i === activeIndex ? activeRef : undefined}
          className={`leading-relaxed transition-all duration-300 ease-md-emphasized cursor-default select-none ${
            !interactive
              ? `${SIZE_REST[lyricsSize]} font-[400] text-on-surface-var`
              : i === activeIndex
                ? `${SIZE_ACTIVE[lyricsSize]} font-[700] text-on-surface scale-[1.02] origin-${lyricsAlign === 'left' ? 'left' : 'center'}`
                : i < activeIndex
                  ? `${SIZE_REST[lyricsSize]} font-[400] text-outline`
                  : `${SIZE_REST[lyricsSize]} font-[400] text-on-surface-var`
          }`}
        >
          {line.text || ' '}
        </p>
      ))}
    </div>
  )
}
